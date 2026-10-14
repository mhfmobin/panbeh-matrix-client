package ir.panbeh.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ir.panbeh.app.PanbehApp
import ir.panbeh.app.data.Phase
import ir.panbeh.app.ui.common.DialogHost
import ir.panbeh.app.ui.common.ToastHost
import ir.panbeh.app.ui.login.LoginScreen
import ir.panbeh.app.ui.theme.Panbeh

@Composable
fun Root() {
    val state = PanbehApp.instance.state
    val phase by state.phase.collectAsStateWithLifecycle()
    val adding by state.adding.collectAsStateWithLifecycle()
    val loginError by state.loginError.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize().background(Panbeh.colors.bg)) {
        when (val p = phase) {
            Phase.Boot -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            Phase.Login -> LoginScreen(loginError, onCancel = if (adding) state::cancelAdd else null)
            is Phase.Error -> Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(p.message, color = Panbeh.colors.danger)
                Button({ state.boot() }) { Text("تلاش دوباره") }
                TextButton({ state.logout() }) { Text("خروج") }
            }
            is Phase.Ready -> MainScreen(p.client)
        }
        ToastHost()
        DialogHost()
        // above everything, dialogs included: lifting them would show the app over the lock
        val locked by ir.panbeh.app.data.AppLock.locked.collectAsStateWithLifecycle()
        if (locked) ir.panbeh.app.ui.lock.LockScreen()
    }
}
