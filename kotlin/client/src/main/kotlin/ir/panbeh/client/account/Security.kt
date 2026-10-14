package ir.panbeh.client.account

import de.connect2x.trixnity.client.MatrixClient
import de.connect2x.trixnity.core.model.UserId
import io.ktor.http.URLBuilder
import io.ktor.http.Url

/** Password change and account deactivation for classic (non-OAuth) accounts; both need the account password. */
class AccountSecurity(private val client: MatrixClient) {
    /** Changes the password and keeps my other devices signed in (logout_devices=false). */
    suspend fun changePassword(current: String, new: String) {
        client.api.authentication.changePassword(new, logoutDevices = false).getOrThrow().withPassword(client.userId, current)
    }

    /** Deactivates the account for good (erase=false). The caller signs out afterwards. */
    suspend fun deactivate(password: String) {
        client.api.authentication.deactivateAccount(erase = false).getOrThrow().withPassword(client.userId, password)
    }
}

/** Links into an OAuth server's account-management page (password, devices, deactivation). */
object AccountUrls {
    const val PROFILE = "org.matrix.profile"
    const val DEACTIVATE = "org.matrix.account_deactivate"

    /** The page opened at [action] when the server supports it, else its start page; null without account management. */
    fun account(uri: String?, supported: Set<String>, action: String? = null): String? {
        val b = URLBuilder(Url(uri ?: return null))
        if (action != null && action in supported) b.parameters["action"] = action
        return b.buildString()
    }

    /** One device's page (to sign it out), or the device list when [deviceId] is null. */
    fun device(uri: String?, supported: Set<String>, deviceId: String? = null): String? {
        val b = URLBuilder(Url(uri ?: return null))
        if (deviceId != null) {
            b.parameters["action"] = if ("org.matrix.device_delete" in supported) "org.matrix.device_delete" else "org.matrix.session_end"
            b.parameters["device_id"] = deviceId
        } else b.parameters["action"] =
            if ("org.matrix.sessions_list" in supported && "org.matrix.devices_list" !in supported) "org.matrix.sessions_list" else "org.matrix.devices_list"
        return b.buildString()
    }
}

/** [AccountUrls] for the logged-in account's server; null when it has no OAuth account management. */
suspend fun MatrixClient.accountManageUrl(action: String? = null): String? {
    val m = api.authentication.getOAuth2ServerMetadata().getOrNull() ?: return null
    return AccountUrls.account(m.accountManagementUri?.toString(), m.accountManagementActionsSupported.orEmpty().map { it.value }.toSet(), action)
}

/** Device-management link of the OAuth server for [deviceId], or the list. */
suspend fun MatrixClient.deviceManageUrl(deviceId: String? = null): String? {
    val m = api.authentication.getOAuth2ServerMetadata().getOrNull() ?: return null
    return AccountUrls.device(m.accountManagementUri?.toString(), m.accountManagementActionsSupported.orEmpty().map { it.value }.toSet(), deviceId)
}
