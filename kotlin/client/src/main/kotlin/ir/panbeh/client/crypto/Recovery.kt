package ir.panbeh.client.crypto

import de.connect2x.trixnity.client.MatrixClient
import de.connect2x.trixnity.client.key
import de.connect2x.trixnity.client.key.KeyBackupService
import de.connect2x.trixnity.client.key.KeySecretService
import de.connect2x.trixnity.client.key.KeyTrustService
import de.connect2x.trixnity.client.store.KeyStore
import de.connect2x.trixnity.client.user
import de.connect2x.trixnity.client.user.getAccountData
import de.connect2x.trixnity.clientserverapi.client.UIA
import de.connect2x.trixnity.clientserverapi.model.uia.AuthenticationRequest
import de.connect2x.trixnity.clientserverapi.model.uia.UIAState
import de.connect2x.trixnity.core.model.events.m.secretstorage.DefaultSecretKeyEventContent
import de.connect2x.trixnity.core.model.events.m.secretstorage.SecretKeyEventContent
import de.connect2x.trixnity.core.model.events.m.secretstorage.SecretKeyEventContent.AesHmacSha2Key
import de.connect2x.trixnity.core.model.keys.RoomKeyBackupSessionData.EncryptedRoomKeyBackupV1SessionData
import de.connect2x.trixnity.core.model.keys.RoomKeyBackupSessionData.EncryptedRoomKeyBackupV1SessionData.RoomKeyBackupV1SessionData
import de.connect2x.trixnity.crypto.SecretType
import de.connect2x.trixnity.crypto.driver.CryptoDriver
import de.connect2x.trixnity.crypto.driver.useAll
import de.connect2x.trixnity.crypto.invoke
import de.connect2x.trixnity.crypto.key.checkRecoveryKey
import de.connect2x.trixnity.crypto.key.decodeRecoveryKey
import de.connect2x.trixnity.crypto.key.recoveryKeyFromPassphrase
import de.connect2x.trixnity.core.model.keys.Key
import ir.panbeh.client.account.NeedsPassword
import ir.panbeh.client.account.WrongPassword
import ir.panbeh.client.account.withPassword
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.qualifier.named
import kotlin.time.Duration.Companion.seconds

/** "ok": this device holds the cross-signing and backup keys; "unlock": the account has secret storage to load; "setup": nothing yet. */
enum class RecoveryState { OK, UNLOCK, SETUP }

/** The user said no to approving the key reset in the auth service. */
class SetupCancelled : Exception("لغو شد")

/** Recovery key / passphrase, cross-signing setup and key-backup restore. */
class Recovery(private val client: MatrixClient) {
    private val keyStore = client.di.get<KeyStore>()

    /** Follows the account's state; waits for the first sync so "setup" isn't reported before the account data arrived. */
    fun state(): Flow<RecoveryState> = client.initialSyncDone.filter { it }.flatMapLatest {
        combine(keyStore.getSecretsFlow(), client.user.getAccountData<DefaultSecretKeyEventContent>()) { secrets, default ->
            val have = secrets.keys
            if (SecretType.M_CROSS_SIGNING_SELF_SIGNING in have && SecretType.M_CROSS_SIGNING_USER_SIGNING in have && SecretType.M_MEGOLM_BACKUP_V1 in have) RecoveryState.OK
            else if (default != null) RecoveryState.UNLOCK else RecoveryState.SETUP
        }
    }.distinctUntilChanged()

    private suspend fun defaultKey(): Pair<String, AesHmacSha2Key> {
        val id = client.user.getAccountData<DefaultSecretKeyEventContent>().first()?.key ?: throw IllegalStateException("بازیابی برای این حساب راه‌اندازی نشده است")
        val info = client.user.getAccountData<SecretKeyEventContent>(id).first() as? AesHmacSha2Key ?: throw IllegalStateException("بازیابی برای این حساب راه‌اندازی نشده است")
        return id to info
    }

    /**
     * Unlocks with the recovery key or the security passphrase: loads the cross-signing and backup keys and signs this device.
     * Old message keys then restore in the background-friendly [restoreKeyBackup], which the caller may launch.
     */
    suspend fun unlock(input: String) {
        val (keyId, info) = defaultKey()
        val key = runCatching { decodeRecoveryKey(input.trim()) }.getOrNull()
            ?: (info.passphrase as? AesHmacSha2Key.SecretStorageKeyPassphrase.Pbkdf2)?.let { runCatching { recoveryKeyFromPassphrase(input, it) }.getOrNull() }
        if (key == null || runCatching { checkRecoveryKey(key, info).isSuccess }.getOrDefault(false).not()) throw IllegalArgumentException("کلید یا عبارت امنیتی درست نیست")
        client.di.get<KeySecretService>().decryptOrCreateMissingSecrets(key, keyId, info)
        client.di.get<KeyTrustService>().checkOwnAdvertisedMasterKeyAndVerifySelf(key, keyId, info).getOrThrow()
    }

    /**
     * Fresh setup: new cross-signing, secret storage and key backup. Returns the recovery key to show once.
     * [password] answers the account's password challenge; without it (OAuth accounts) the server's auth service must approve
     * a reset: [approve] gets its URL, shows it, and returns true once the user confirmed there (false cancels).
     * [passphrase] makes the recovery key derivable from a security passphrase instead of random.
     */
    suspend fun setup(password: String? = null, passphrase: String? = null, approve: suspend (url: String) -> Boolean = { false }): String {
        val b = if (passphrase.isNullOrEmpty()) client.key.bootstrapCrossSigning() else client.key.bootstrapCrossSigningFromPassphrase(passphrase)
        var uia = b.result.getOrThrow()
        while (true) when (val cur = uia) {
            is UIA.Success -> return b.recoveryKey
            is UIA.Step -> if (password != null) {
                cur.withPassword(client.userId, password)
                return b.recoveryKey
            } else {
                val url = approvalUrl(cur.state) ?: throw NeedsPassword()
                if (!approve(url)) throw SetupCancelled()
                uia = cur.authenticate(AuthenticationRequest.OAuth2).getOrThrow()
            }
            is UIA.Error -> throw WrongPassword()
        }
    }

    /** Fetches every session from the server key backup into the store. Returns how many were new or improved; needs [unlock] first. */
    suspend fun restoreKeyBackup(onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): Int {
        val version = withTimeoutOrNull(30.seconds) { client.di.get<KeyBackupService>(named<KeyBackupService>()).version.filterNotNull().first() }
            ?: throw IllegalStateException("پشتیبان کلیدها در دسترس نیست")
        val privateKey = keyStore.getSecrets()[SecretType.M_MEGOLM_BACKUP_V1]?.decryptedPrivateKey ?: throw IllegalStateException("کلید پشتیبان هنوز باز نشده است")
        val driver = client.di.get<CryptoDriver>()
        val rooms = client.api.key.getRoomKeys(version.version).getOrThrow().rooms
        val total = rooms.values.sumOf { it.sessions.size }
        val megolm = MegolmStore(client)
        var done = 0
        for ((roomId, room) in rooms) for ((sessionId, data) in room.sessions) {
            runCatching {
                val enc = data.sessionData as EncryptedRoomKeyBackupV1SessionData
                val json = useAll({ driver.pk.decryption(privateKey) }, { driver.pk.message(enc) }) { d, m -> d.decrypt(m) }
                val s = client.api.json.decodeFromString<RoomKeyBackupV1SessionData>(json)
                val signing = s.senderClaimedKeys.filterIsInstance<Key.Ed25519Key>().firstOrNull() ?: error("no sender key")
                megolm.import(ExportedMegolm(roomId, sessionId, s.senderKey, signing.value, s.forwardingKeyChain, s.sessionKey), trusted = false, backedUp = true)
            }
            onProgress(++done, total)
        }
        return total
    }

    private fun approvalUrl(state: UIAState): String? = state.parameter.orEmpty().entries.firstNotNullOfOrNull { (type, p) ->
        if (type.name != "m.oauth" && type.name != "org.matrix.cross_signing_reset") null
        else when (p) {
            is UIAState.Parameter.OAuth2 -> p.url
            is UIAState.Parameter.Unknown -> ((p.raw as? JsonObject)?.get("url") as? JsonPrimitive)?.contentOrNull
            else -> null
        }
    }
}
