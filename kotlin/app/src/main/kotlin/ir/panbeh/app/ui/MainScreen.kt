package ir.panbeh.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import de.connect2x.trixnity.client.MatrixClient

/** Signed in: the chat list with screens stacked above it. */
@Composable
fun MainScreen(client: MatrixClient) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(client.userId.full) }
}
