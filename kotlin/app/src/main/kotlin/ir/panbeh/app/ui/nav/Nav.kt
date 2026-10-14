package ir.panbeh.app.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.staticCompositionLocalOf

/** Screens above the chat list. The list itself is the bottom of the stack and is always there. */
sealed interface Screen {
    data class Room(val roomId: String, val eventId: String? = null) : Screen
    data class Thread(val roomId: String, val rootId: String) : Screen
    data class RoomInfo(val roomId: String) : Screen
    data class Profile(val userId: String, val roomId: String? = null) : Screen
    data class Settings(val page: String = "main") : Screen
    data class Media(val roomId: String, val eventId: String) : Screen
    data object NewChat : Screen
    data object Calls : Screen
    data object Call : Screen
    data class Other(val key: String, val content: @Composable () -> Unit) : Screen
}

class Navigator {
    val stack = mutableStateListOf<Screen>()
    val top: Screen? get() = stack.lastOrNull()
    fun push(s: Screen) { if (stack.lastOrNull() != s) stack.add(s) }
    fun pop(): Boolean = if (stack.isEmpty()) false else { stack.removeAt(stack.lastIndex); true }
    /** Replace everything above the chat list. */
    fun reset(vararg s: Screen) { stack.clear(); stack.addAll(s) }
    fun openRoom(roomId: String, eventId: String? = null) {
        stack.removeAll { it is Screen.Room || it is Screen.Thread || it is Screen.RoomInfo }
        push(Screen.Room(roomId, eventId))
    }
}

val LocalNav = staticCompositionLocalOf<Navigator> { error("no navigator") }
