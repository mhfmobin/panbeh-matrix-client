package ir.panbeh.client.crypto

import de.connect2x.trixnity.client.MatrixClient
import de.connect2x.trixnity.client.store.AccountStore
import de.connect2x.trixnity.client.store.OlmCryptoStore
import de.connect2x.trixnity.client.store.StoreTransactionManager
import de.connect2x.trixnity.client.store.repository.InboundMegolmSessionRepository
import de.connect2x.trixnity.core.model.RoomId
import de.connect2x.trixnity.core.model.keys.ExportedSessionKeyValue
import de.connect2x.trixnity.core.model.keys.KeyValue.Curve25519KeyValue
import de.connect2x.trixnity.core.model.keys.KeyValue.Ed25519KeyValue
import de.connect2x.trixnity.crypto.driver.CryptoDriver
import de.connect2x.trixnity.crypto.driver.megolm.InboundGroupSession
import de.connect2x.trixnity.crypto.driver.useAll
import de.connect2x.trixnity.crypto.invoke
import de.connect2x.trixnity.crypto.of
import de.connect2x.trixnity.crypto.olm.StoredInboundMegolmSession

/** A Megolm session in the shape key files and key backups use (exported at its first known index). */
internal class ExportedMegolm(
    val roomId: RoomId, val sessionId: String, val senderKey: Curve25519KeyValue, val senderSigningKey: Ed25519KeyValue,
    val forwardingChain: List<Curve25519KeyValue>, val sessionKey: ExportedSessionKeyValue,
)

/** Trixnity's inbound Megolm sessions (OlmCryptoStore / InboundMegolmSessionRepository), read and written directly. */
internal class MegolmStore(client: MatrixClient) {
    private val di = client.di
    private val tm = di.get<StoreTransactionManager>()
    private val store = di.get<OlmCryptoStore>()
    private val repo = di.get<InboundMegolmSessionRepository>()
    private val accounts = di.get<AccountStore>()
    private val driver = di.get<CryptoDriver>()

    private suspend fun pickleKey() = driver.key.pickleKey(accounts.getAccount()?.olmPickleKey)

    /** Every stored session, exported. */
    suspend fun exportAll(): List<ExportedMegolm> {
        val all = tm.readTransaction { repo.getAll() }
        val key = pickleKey()
        return all.map { s ->
            val sk = driver.megolm.inboundGroupSession.fromPickle(s.pickled, key).use(InboundGroupSession::exportAtFirstKnownIndex)
            ExportedMegolm(s.roomId, s.sessionId, s.senderKey, s.senderSigningKey, s.forwardingCurve25519KeyChain, ExportedSessionKeyValue.of(sk))
        }
    }

    /** Stores an exported session unless we already know it from an earlier index. */
    suspend fun import(e: ExportedMegolm, trusted: Boolean, backedUp: Boolean) {
        val key = pickleKey()
        val (firstIndex, pickled) = useAll(
            { driver.megolm.exportedSessionKey(e.sessionKey) },
            { driver.megolm.inboundGroupSession.import(it) },
        ) { _, session -> session.firstKnownIndex to session.pickle(key) }
        tm.writeTransaction {
            store.updateInboundMegolmSession(e.sessionId, e.roomId) { old ->
                if (old != null && old.firstKnownIndex <= firstIndex.toLong()) old
                else StoredInboundMegolmSession(
                    senderKey = e.senderKey, senderSigningKey = e.senderSigningKey, sessionId = e.sessionId, roomId = e.roomId,
                    firstKnownIndex = firstIndex.toLong(), hasBeenBackedUp = backedUp, isTrusted = trusted,
                    forwardingCurve25519KeyChain = e.forwardingChain, pickled = pickled,
                )
            }
        }
    }
}
