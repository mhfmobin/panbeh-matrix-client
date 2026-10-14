package ir.panbeh.client.crypto

import de.connect2x.trixnity.client.MatrixClient
import de.connect2x.trixnity.core.model.RoomId
import de.connect2x.trixnity.core.model.keys.ExportedSessionKeyValue
import de.connect2x.trixnity.core.model.keys.KeyValue.Curve25519KeyValue
import de.connect2x.trixnity.core.model.keys.KeyValue.Ed25519KeyValue
import ir.panbeh.core.keyfile.decryptKeyFile
import ir.panbeh.core.keyfile.encryptKeyFile
import ir.panbeh.core.util.string
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

private const val ALG = "m.megolm.v1.aes-sha2"

/** Room-key export and import in Element's key-file format (src/keyfile.ts via core). */
class KeyFiles(client: MatrixClient) {
    private val store = MegolmStore(client)

    /** All room keys as an encrypted key file (text). */
    suspend fun export(passphrase: String, rounds: Int = 500_000): String {
        val keys = store.exportAll()
        val json = buildJsonArray {
            for (k in keys) add(buildJsonObject {
                put("algorithm", ALG)
                put("room_id", k.roomId.full)
                put("sender_key", k.senderKey.value)
                put("sender_claimed_keys", buildJsonObject { put("ed25519", k.senderSigningKey.value) })
                put("session_id", k.sessionId)
                put("session_key", k.sessionKey.value)
                put("forwarding_curve25519_key_chain", buildJsonArray { k.forwardingChain.forEach { add(JsonPrimitive(it.value)) } })
            })
        }.toString()
        return withContext(Dispatchers.Default) { encryptKeyFile(json, passphrase, rounds) }
    }

    /**
     * Imports a key file; [onProgress] gets (done, total). Returns the number of keys in the file.
     * @throws ir.panbeh.core.keyfile.KeyFileException for a wrong file, version or passphrase.
     */
    suspend fun import(file: String, passphrase: String, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): Int {
        val arr = Json.parseToJsonElement(withContext(Dispatchers.Default) { decryptKeyFile(file, passphrase) }).jsonArray
        arr.forEachIndexed { i, el ->
            runCatching { parse(el.jsonObject) }.getOrNull()?.let { runCatching { store.import(it, trusted = false, backedUp = false) } }
            onProgress(i + 1, arr.size)
        }
        return arr.size
    }

    private fun parse(o: JsonObject): ExportedMegolm? {
        if (o.string("algorithm") != ALG) return null
        val claimed = (o["sender_claimed_keys"] as? JsonObject)?.string("ed25519") ?: return null
        return ExportedMegolm(
            RoomId(o.string("room_id") ?: return null), o.string("session_id") ?: return null,
            Curve25519KeyValue(o.string("sender_key") ?: return null), Ed25519KeyValue(claimed),
            (o["forwarding_curve25519_key_chain"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.let(::Curve25519KeyValue) },
            ExportedSessionKeyValue(o.string("session_key") ?: return null),
        )
    }
}
