package ir.panbeh.client.calls

import de.connect2x.trixnity.client.MatrixClient
import de.connect2x.trixnity.client.room
import de.connect2x.trixnity.core.model.RoomId
import de.connect2x.trixnity.core.model.UserId
import de.connect2x.trixnity.core.model.events.ToDeviceEventContent
import de.connect2x.trixnity.core.model.events.m.RelatesTo
import de.connect2x.trixnity.core.serialization.events.EventContentSerializerMappings
import de.connect2x.trixnity.core.serialization.events.invoke
import de.connect2x.trixnity.core.serialization.events.messageOf
import de.connect2x.trixnity.core.serialization.events.toDeviceOf
import de.connect2x.trixnity.crypto.olm.OlmEncryptionService
import de.connect2x.trixnity.crypto.olm.OlmEventHandler
import ir.panbeh.client.events.Raw
import ir.panbeh.client.events.RawMessage
import ir.panbeh.client.events.RawSerializer
import ir.panbeh.core.util.number
import ir.panbeh.core.util.string
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.security.SecureRandom
import java.util.Base64

// ---------------------------------------------------------------------------------------------------------------------
// E2EE keys for the LiveKit frame encryption, the way matrix-js-sdk's MatrixRTC session and Element Call exchange them:
// each device makes its own media key (16 random bytes, index 0..255) and hands it to every other member device of the call as
// an `io.element.call.encryption_keys` event. Assumptions are listed in the module report.
// ---------------------------------------------------------------------------------------------------------------------

/** `io.element.call.encryption_keys` as a room event (the older transport; encrypted by the room like any message). */
class EncryptionKeysMessage(raw: JsonObject, relatesTo: RelatesTo?) : RawMessage(raw, relatesTo) {
    override fun copyWith(relatesTo: RelatesTo?) = EncryptionKeysMessage(raw, relatesTo)
}

/** `io.element.call.encryption_keys` as a to-device event (sent olm-encrypted to each member device). */
class EncryptionKeysToDevice(raw: JsonObject) : Raw(raw), ToDeviceEventContent

/** Event types the call keys need registered in the client's serializer mappings (add to `panbehMappings`). */
val callMappings: EventContentSerializerMappings = EventContentSerializerMappings {
    messageOf<EncryptionKeysMessage>(CallTypes.ENCRYPTION_KEYS, RawSerializer("EncryptionKeysMessage", ::EncryptionKeysMessage))
    toDeviceOf<EncryptionKeysToDevice>(CallTypes.ENCRYPTION_KEYS, RawSerializer("EncryptionKeysToDevice") { o, _ -> EncryptionKeysToDevice(o) })
}

/** A key for one participant's media: [identity] is its LiveKit identity "@user:server:DEVICE". */
class ParticipantKey(val userId: String, val deviceId: String, val index: Int, val key: ByteArray) {
    val identity get() = "$userId:$deviceId"
    override fun equals(other: Any?) = other is ParticipantKey && identity == other.identity && index == other.index && key.contentEquals(other.key)
    override fun hashCode() = 31 * (identity.hashCode() * 31 + index) + key.contentHashCode()
    override fun toString() = "ParticipantKey($identity, #$index)"
}

private val b64 = Base64.getEncoder().withoutPadding()
fun encodeKey(key: ByteArray): String = b64.encodeToString(key)

/** Base64 as Element writes it (standard alphabet, unpadded), also url-safe and padded. */
fun decodeKey(s: String): ByteArray? = runCatching {
    Base64.getDecoder().decode(s.replace('-', '+').replace('_', '/').trimEnd('=').let { it + "=".repeat((4 - it.length % 4) % 4) })
}.getOrNull()

/** The to-device content for handing one key to a device. */
fun keysToDeviceContent(roomId: String, deviceId: String, index: Int, key: ByteArray, now: Long) = buildJsonObject {
    putJsonObject("keys") { put("index", index); put("key", encodeKey(key)) }
    putJsonObject("member") { put("claimed_device_id", deviceId) }
    put("room_id", roomId)
    putJsonObject("session") { put("application", CallTypes.APPLICATION); put("call_id", ""); put("scope", CallTypes.ROOM_SCOPE) }
    put("sent_ts", now)
}

/** The room-event content for handing keys to everyone in the room. */
fun keysRoomContent(deviceId: String, keys: List<Pair<Int, ByteArray>>, now: Long) = buildJsonObject {
    putJsonArray("keys") { keys.forEach { (i, k) -> addJsonObject { put("index", i); put("key", encodeKey(k)) } } }
    put("device_id", deviceId); put("call_id", ""); put("sent_ts", now)
}

/** Keys in a received to-device or room event: (sender's claimed device id, [(index, key)]), or null when it isn't ours to read. */
fun parseKeys(c: JsonObject, roomId: String?): Pair<String, List<Pair<Int, ByteArray>>>? {
    c.string("room_id")?.let { if (roomId != null && it != roomId) return null }
    ((c["session"] as? JsonObject)?.string("call_id"))?.let { if (it != "") return null }
    (c["call_id"] as? JsonPrimitive)?.contentOrNull?.let { if (it != "") return null }
    val device = ((c["member"] as? JsonObject)?.string("claimed_device_id")) ?: c.string("device_id") ?: return null
    val items: List<JsonObject> = when (val k = c["keys"]) {
        is JsonObject -> listOf(k)
        is JsonArray -> k.mapNotNull { it as? JsonObject }
        else -> return null
    }
    val out = items.mapNotNull { o ->
        val idx = o.number("index")?.toInt() ?: return@mapNotNull null
        val key = o.string("key")?.let(::decodeKey) ?: return@mapNotNull null
        idx to key
    }
    return if (out.isEmpty()) null else device to out
}

/** Where keys travel. */
interface KeyTransport {
    /** Hands [keys] (index, bytes) to these (userId, deviceId) members. */
    suspend fun send(roomId: RoomId, myDevice: String, to: Collection<Pair<String, String>>, keys: List<Pair<Int, ByteArray>>)
    /** (sender's user id, the rest as [parseKeys] gives it), for every event that arrives. */
    val received: Flow<IncomingKeys>
}

class IncomingKeys(val roomId: String?, val userId: String, val content: JsonObject)

/** Room-event transport: one event, encrypted by the room, for everyone. Receives from any member. */
class RoomKeyTransport(private val client: MatrixClient) : KeyTransport {
    override suspend fun send(roomId: RoomId, myDevice: String, to: Collection<Pair<String, String>>, keys: List<Pair<Int, ByteArray>>) {
        client.room.sendMessage(roomId) { content(EncryptionKeysMessage(keysRoomContent(myDevice, keys, System.currentTimeMillis()), null)) }
    }
    override val received: Flow<IncomingKeys> get() = client.callEvents().filter { it.type == CallTypes.ENCRYPTION_KEYS }
        .filter { it.sender != client.userId.full }
        .map { IncomingKeys(it.roomId.full, it.sender, it.content) }
}

/** To-device transport: olm-encrypted per member device (what current Element Call and Element X send and expect). */
class ToDeviceKeyTransport(private val client: MatrixClient) : KeyTransport {
    override suspend fun send(roomId: RoomId, myDevice: String, to: Collection<Pair<String, String>>, keys: List<Pair<Int, ByteArray>>) {
        val olm = client.di.get<OlmEncryptionService>()
        val now = System.currentTimeMillis()
        for ((index, key) in keys) {
            val content = EncryptionKeysToDevice(keysToDeviceContent(roomId.full, myDevice, index, key, now))
            val targets = to.map { UserId(it.first) to it.second }.toSet()
            if (targets.isEmpty()) continue
            val enc = olm.encryptOlm(content, targets)
            val ok = enc.mapNotNull { (k, v) -> v.getOrNull()?.let { k to it } }
            if (ok.isEmpty()) continue
            client.api.user.sendToDevice(ok.groupBy({ it.first.first }) { it.first.second to it.second }.mapValues { (_, l) -> l.toMap() }).getOrThrow()
        }
    }

    override val received: Flow<IncomingKeys> = callbackFlow {
        val handler = client.di.get<OlmEventHandler>()
        val unsub = handler.subscribe { container ->
            val c = container.decrypted.content
            val raw = when (c) {
                is EncryptionKeysToDevice -> c.raw
                is de.connect2x.trixnity.core.model.events.UnknownEventContent -> if (c.eventType == CallTypes.ENCRYPTION_KEYS) c.raw else null
                else -> null
            } ?: return@subscribe
            trySend(IncomingKeys(raw.string("room_id"), container.decrypted.sender.full, raw))
        }
        awaitClose { unsub() }
    }
}

/**
 * Makes our media key, hands it to the other members' devices (new members get it when they show up), rotates it when someone
 * leaves, and reports every key that arrives. Run [start] after joining; [stop] when leaving.
 */
class CallKeyManager(
    private val scope: CoroutineScope,
    private val roomId: RoomId,
    private val myUser: String,
    private val myDevice: String,
    private val members: Flow<List<CallMembership>>,
    private val transports: List<KeyTransport>,
    private val rotateDelayMs: Long = 3_000,
    private val random: java.util.Random = SecureRandom(),
) {
    private var index = -1
    private val _local = MutableStateFlow<ParticipantKey?>(null)
    /** Our current key; set it on the LiveKit key provider for our own identity. */
    val local: StateFlow<ParticipantKey?> = _local.asStateFlow()
    private val _remote = MutableSharedFlow<ParticipantKey>(replay = 256, extraBufferCapacity = 64)
    /** Keys of other participants, as they arrive (the latest ones replay to a new collector). */
    val remote: SharedFlow<ParticipantKey> = _remote.asSharedFlow()
    private val seen = HashSet<ParticipantKey>()
    private val jobs = mutableListOf<Job>()
    private val sentTo = HashSet<Pair<String, String>>()
    private val mu = Mutex()
    private var rotating: Job? = null

    private fun newKey(): ParticipantKey {
        index = (index + 1) % 256
        return ParticipantKey(myUser, myDevice, index, ByteArray(16).also { random.nextBytes(it) })
    }

    fun start() {
        _local.value = newKey()
        jobs += scope.launch {
            var known = emptySet<Pair<String, String>>()
            members.collect { list ->
                val others = list.filter { !(it.userId == myUser && it.deviceId == myDevice) }.map { it.userId to it.deviceId }.toSet()
                val joined = others - known
                val left = known - others
                known = others
                mu.withLock { sentTo.retainAll(others) }
                if (left.isNotEmpty()) rotateSoon(others) else if (joined.isNotEmpty()) distribute(joined)
            }
        }
        for (t in transports) jobs += scope.launch { t.received.collect(::onReceived) }
    }

    fun stop() { jobs.forEach { it.cancel() }; jobs.clear(); rotating?.cancel() }

    private fun rotateSoon(others: Set<Pair<String, String>>) {
        rotating?.cancel()
        rotating = scope.launch {
            delay(rotateDelayMs)
            mu.withLock { sentTo.clear(); _local.value = newKey() }
            distribute(others)
        }
    }

    private suspend fun distribute(to: Set<Pair<String, String>>) = mu.withLock {
        val key = _local.value ?: return@withLock
        val targets = to - sentTo
        if (targets.isEmpty()) return@withLock
        for (t in transports) runCatching { t.send(roomId, myDevice, targets, listOf(key.index to key.key)) }
        sentTo += targets
    }

    private suspend fun onReceived(k: IncomingKeys) {
        if (k.userId == myUser && parseKeys(k.content, roomId.full)?.first == myDevice) return
        val (device, keys) = parseKeys(k.content, roomId.full) ?: return
        for ((i, bytes) in keys) {
            val pk = ParticipantKey(k.userId, device, i, bytes)
            if (seen.add(pk)) _remote.emit(pk)
        }
    }

    /** Resends our key to every other member (after a reconnect). */
    suspend fun resend() { mu.withLock { sentTo.clear() }; distribute(members.first().filter { !(it.userId == myUser && it.deviceId == myDevice) }.map { it.userId to it.deviceId }.toSet()) }
}
