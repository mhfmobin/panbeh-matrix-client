package ir.panbeh.app.notify

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/** A button on the incoming-call notification: "open" | "answer" | "decline". */
data class CallAction(val action: String, val roomId: String, val eventId: String?, val video: Boolean)

/** State shared between the UI and the notification code. */
object NotifyState {
    /** The chat on screen, set by the UI (null on the chat list); its notifications are skipped and cleared while the app is visible. */
    val openRoom = MutableStateFlow<String?>(null)

    /** "Decline" from the incoming-call notification (CallReceiver); the call code collects it. */
    val callActions = MutableSharedFlow<CallAction>(extraBufferCapacity = 8)
}
