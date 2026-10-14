package ir.panbeh.client.crypto

import de.connect2x.trixnity.client.MatrixClient
import de.connect2x.trixnity.client.key
import de.connect2x.trixnity.core.model.UserId
import de.connect2x.trixnity.crypto.key.DeviceTrustLevel
import de.connect2x.trixnity.crypto.key.UserTrustLevel
import ir.panbeh.client.account.withPassword
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

/** Cross-signing verification of one of my devices. [NO_KEYS]: the device has no encryption keys (or they aren't known yet). */
enum class DeviceTrust { VERIFIED, UNVERIFIED, NO_KEYS }

/** One of my sessions. [lastSeenTs] is in ms. */
data class DeviceInfo(
    val deviceId: String, val displayName: String?, val lastSeenTs: Long?, val lastSeenIp: String?,
    val trust: DeviceTrust, val current: Boolean,
) {
    val crossSigningVerified get() = trust == DeviceTrust.VERIFIED
}

/** Cross-signing trust of another user. [known]: they have cross-signing at all. */
data class UserTrust(val known: Boolean, val verified: Boolean)

private fun DeviceTrustLevel.toTrust() = when (this) {
    is DeviceTrustLevel.CrossSigned -> if (verified) DeviceTrust.VERIFIED else DeviceTrust.UNVERIFIED
    is DeviceTrustLevel.Valid -> if (verified) DeviceTrust.VERIFIED else DeviceTrust.UNVERIFIED
    DeviceTrustLevel.NotCrossSigned, DeviceTrustLevel.NotTrusted, DeviceTrustLevel.Blocked -> DeviceTrust.UNVERIFIED
    else -> DeviceTrust.NO_KEYS
}

/** My device list, trust of other users and ending sessions. */
@OptIn(ExperimentalCoroutinesApi::class)
class Devices(private val client: MatrixClient) {
    private val reload = MutableStateFlow(0)

    /** The server's list (current device first, then newest seen) with live verification state; call [refresh] to re-read the list. */
    fun watch(): Flow<List<DeviceInfo>> = reload.flatMapLatest {
        flow {
            val list = client.api.device.getDevices().getOrThrow()
                .sortedWith(compareByDescending<de.connect2x.trixnity.clientserverapi.model.device.Device> { it.deviceId == client.deviceId }.thenByDescending { it.lastSeenTs ?: 0 })
            if (list.isEmpty()) emit(emptyList<DeviceInfo>())
            else emitAll(combine(list.map { d ->
                client.key.getTrustLevel(client.userId, d.deviceId).map { it.toTrust() }.onStart { emit(DeviceTrust.NO_KEYS) }
                    .map { DeviceInfo(d.deviceId, d.displayName, d.lastSeenTs, d.lastSeenIp, it, d.deviceId == client.deviceId) }
            }) { it.toList() })
        }
    }

    /** Re-reads the list from the server (after deleting, or when the user returns from the account page). */
    fun refresh() { reload.value++ }

    /**
     * Signs devices out. Tries without auth, then with [password]; throws NeedsPassword when the server wants one and none was given,
     * WrongPassword when it was refused.
     */
    suspend fun delete(deviceIds: List<String>, password: String? = null) {
        client.api.device.deleteDevices(deviceIds).getOrThrow().withPassword(client.userId, password)
        refresh()
    }

    /** Whether [userId] is cross-signing verified by me, live. */
    fun userTrust(userId: String): Flow<UserTrust> = client.key.getTrustLevel(UserId(userId)).map {
        when (it) {
            is UserTrustLevel.CrossSigned -> UserTrust(true, it.verified)
            is UserTrustLevel.NotAllDevicesCrossSigned -> UserTrust(true, false)
            UserTrustLevel.Blocked -> UserTrust(true, false)
            else -> UserTrust(false, false)
        }
    }
}
