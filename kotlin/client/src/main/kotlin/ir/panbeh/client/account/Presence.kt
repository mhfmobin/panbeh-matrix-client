package ir.panbeh.client.account

import de.connect2x.trixnity.client.MatrixClient
import de.connect2x.trixnity.core.model.events.m.Presence

/** The `shareLastSeen` privacy setting: off keeps me offline for everyone. */
object PresencePrivacy {
    /** Presence to sync with: offline when not sharing, "unavailable" for background/headless syncs, else online. */
    fun syncPresence(shareLastSeen: Boolean, background: Boolean = false): Presence =
        if (!shareLastSeen) Presence.OFFLINE else if (background) Presence.UNAVAILABLE else Presence.ONLINE

    /** Applies a changed setting now: tells the server and restarts the sync loop with the new presence. */
    suspend fun apply(client: MatrixClient, shareLastSeen: Boolean) {
        val p = syncPresence(shareLastSeen)
        client.api.user.setPresence(client.userId, p).getOrThrow()
        client.cancelSync()
        client.startSync(p)
    }
}
