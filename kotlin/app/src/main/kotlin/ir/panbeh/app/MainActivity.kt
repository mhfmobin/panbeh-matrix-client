package ir.panbeh.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import ir.panbeh.app.ui.theme.PanbehTheme
import ir.panbeh.core.APP_NAME

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            PanbehTheme {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(APP_NAME) }
            }
        }
    }
}
