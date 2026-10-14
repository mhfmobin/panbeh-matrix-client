package ir.panbeh.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.connect2x.trixnity.client.MatrixClient
import ir.panbeh.app.PanbehApp
import ir.panbeh.app.ui.nav.LocalNav
import ir.panbeh.app.ui.nav.Navigator
import ir.panbeh.app.ui.nav.Screen
import ir.panbeh.app.ui.screens.CallScreen
import ir.panbeh.app.ui.screens.CallsScreen
import ir.panbeh.app.ui.screens.ChatListScreen
import ir.panbeh.app.ui.screens.MediaScreen
import ir.panbeh.app.ui.screens.NewChatScreen
import ir.panbeh.app.ui.screens.ProfileScreen
import ir.panbeh.app.ui.screens.RoomInfoScreen
import ir.panbeh.app.ui.screens.RoomScreen
import ir.panbeh.app.ui.screens.SettingsScreen
import ir.panbeh.app.ui.screens.ThreadScreen
import ir.panbeh.app.ui.theme.Panbeh

/** The signed-in client, for screens below MainScreen. */
val LocalClient = androidx.compose.runtime.staticCompositionLocalOf<MatrixClient> { error("no client") }

/** Signed in: the chat list with screens stacked above it (RTL: a screen enters from the left). */
@Composable
fun MainScreen(client: MatrixClient) {
    val nav = remember(client) { Navigator() }
    val state = PanbehApp.instance.state
    val pendingRoom by state.pendingRoom.collectAsStateWithLifecycle()
    LaunchedEffect(pendingRoom) { pendingRoom?.let { nav.openRoom(it); state.pendingRoom.value = null } }

    val holder = rememberSaveableStateHolder()
    CompositionLocalProvider(LocalNav provides nav, LocalClient provides client) {
        BackHandler(nav.stack.isNotEmpty()) { nav.pop() }
        AnimatedContent(
            targetState = nav.stack.lastOrNull(),
            transitionSpec = {
                // pushed if the screen we leave is still on the stack (or was the list)
                val forward = targetState != null && (initialState == null || initialState in nav.stack)
                val d = tween<androidx.compose.ui.unit.IntOffset>(250)
                if (forward) (slideInHorizontally(d) { -it } + fadeIn()) togetherWith (slideOutHorizontally(d) { it / 4 } + fadeOut())
                else (slideInHorizontally(d) { it / 4 } + fadeIn()) togetherWith (slideOutHorizontally(d) { -it } + fadeOut())
            },
            label = "nav",
        ) { screen ->
            Box(Modifier.fillMaxSize().background(Panbeh.colors.bg)) {
                holder.SaveableStateProvider(screen?.toString() ?: "list") {
                    when (screen) {
                        null -> ChatListScreen()
                        is Screen.Room -> RoomScreen(screen.roomId, screen.eventId)
                        is Screen.Thread -> ThreadScreen(screen.roomId, screen.rootId)
                        is Screen.RoomInfo -> RoomInfoScreen(screen.roomId)
                        is Screen.Profile -> ProfileScreen(screen.userId, screen.roomId)
                        is Screen.Settings -> SettingsScreen(screen.page)
                        is Screen.Media -> MediaScreen(screen.roomId, screen.eventId)
                        Screen.NewChat -> NewChatScreen()
                        Screen.Calls -> CallsScreen()
                        Screen.Call -> CallScreen()
                        is Screen.Other -> screen.content()
                    }
                }
            }
        }
    }
}
