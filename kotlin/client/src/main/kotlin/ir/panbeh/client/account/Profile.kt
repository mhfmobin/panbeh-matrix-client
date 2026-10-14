package ir.panbeh.client.account

import de.connect2x.trixnity.client.MatrixClient
import de.connect2x.trixnity.clientserverapi.model.user.ProfileField
import de.connect2x.trixnity.core.model.UserId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map

/** A display name and photo ([avatarUrl] is an mxc:// URI). */
data class ProfileInfo(val displayName: String?, val avatarUrl: String?)

/** My own profile; Trixnity keeps a copy in its store, so [profile] is there immediately and follows changes. */
class MyProfile(private val client: MatrixClient) {
    val profile: Flow<ProfileInfo> = client.profile.filterNotNull().map {
        ProfileInfo(it[ProfileField.DisplayName]?.value?.takeIf(String::isNotEmpty), it[ProfileField.AvatarUrl]?.value?.takeIf(String::isNotEmpty))
    }

    /** Someone's profile from the server (fresher than their room membership), null when it can't be read. */
    suspend fun of(userId: String): ProfileInfo? = client.api.user.getProfile(UserId(userId)).getOrNull()?.let {
        ProfileInfo(it[ProfileField.DisplayName]?.value?.takeIf(String::isNotEmpty), it[ProfileField.AvatarUrl]?.value?.takeIf(String::isNotEmpty))
    }

    suspend fun setDisplayName(name: String) = client.setProfileField(ProfileField.DisplayName(name.trim())).getOrThrow()

    /** Uploads [bytes] (already scaled/compressed by the platform) as my photo; returns the mxc URI. */
    suspend fun setAvatar(bytes: ByteArray, contentType: String): String {
        val mxc = client.uploadPlain(bytes, contentType)
        client.setProfileField(ProfileField.AvatarUrl(mxc)).getOrThrow()
        return mxc
    }

    suspend fun removeAvatar() = client.deleteProfileField(ProfileField.AvatarUrl).getOrThrow()
}
