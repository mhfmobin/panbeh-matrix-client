package ir.panbeh.app.ui.login

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ir.panbeh.app.PanbehApp
import ir.panbeh.app.R
import ir.panbeh.app.ui.common.Wallpaper
import ir.panbeh.app.ui.theme.Panbeh
import ir.panbeh.client.LoginMode
import ir.panbeh.client.errText
import ir.panbeh.client.loginErrText
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class Mode { NONE, CHECKING, OAUTH, PASSWORD }

@Composable
fun LoginScreen(initialError: String? = null, onCancel: (() -> Unit)? = null) {
    val app = PanbehApp.instance
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var server by remember { mutableStateOf(app.platform.store["panbeh.lastServer"] ?: "") }
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(initialError ?: "") }
    var inBrowser by remember { mutableStateOf(false) }
    var mode by remember { mutableStateOf(Mode.NONE) }

    suspend fun check(s: String) {
        mode = Mode.CHECKING
        mode = runCatching { app.accounts.loginMode(s) }.fold(
            onSuccess = { if (it == LoginMode.OAUTH) Mode.OAUTH else Mode.PASSWORD },
            onFailure = { error = errText(it); Mode.NONE },
        )
    }
    // once typing stops, ask the server how it logs in
    LaunchedEffect(server) {
        mode = Mode.NONE
        if (server.isBlank()) return@LaunchedEffect
        delay(700)
        check(server)
    }

    fun submit() {
        if (mode == Mode.NONE) { scope.launch { check(server) }; return }
        if (mode == Mode.CHECKING) return
        busy = true; error = ""
        scope.launch {
            app.platform.store["panbeh.lastServer"] = server
            runCatching {
                if (mode == Mode.OAUTH) {
                    val url = app.accounts.startOAuth(server)
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    inBrowser = true; busy = false
                } else {
                    app.state.ready(app.accounts.loginPassword(server, user.trim(), password))
                }
            }.onFailure { error = loginErrText(it); busy = false }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Wallpaper(Modifier.fillMaxSize())
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
        ) {
            Surface(shape = RoundedCornerShape(20.dp), color = Panbeh.colors.panel, shadowElevation = 2.dp, modifier = Modifier.widthIn(max = 380.dp).fillMaxWidth()) {
                Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Image(painterResource(R.mipmap.ic_launcher_round), null, Modifier.size(72.dp))
                    Text("ورود به ماتریکس", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    LtrField("سرور", server, { server = it; error = "" }, "matrix.example.org:8448", KeyboardType.Uri, ImeAction.Go, onIme = ::submit)
                    if (mode == Mode.PASSWORD) {
                        LtrField("نام کاربری", user, { user = it }, "alice", KeyboardType.Text, ImeAction.Next) {}
                        LtrField("رمز عبور", password, { password = it }, null, KeyboardType.Password, ImeAction.Go, secret = true, onIme = ::submit)
                    }
                    if (error.isNotEmpty()) Text(error, color = Panbeh.colors.danger)
                    if (inBrowser && error.isEmpty()) Text("ورود را در مرورگر ادامه دهید؛ پس از آن به پنبه برمی‌گردید.", color = Panbeh.colors.muted)
                    Button(::submit, Modifier.fillMaxWidth(), enabled = !busy && mode != Mode.CHECKING && server.isNotBlank()) {
                        Text(if (busy) "در حال ورود…" else when (mode) {
                            Mode.NONE -> "ادامه"; Mode.CHECKING -> "در حال بررسی سرور…"; Mode.OAUTH -> "ادامه در صفحه‌ی ورود سرور"; Mode.PASSWORD -> "ورود"
                        })
                    }
                    if (onCancel != null) TextButton(onCancel) { Text("انصراف") }
                }
            }
        }
    }
}

@Composable
private fun LtrField(
    label: String, value: String, onChange: (String) -> Unit, placeholder: String?, type: KeyboardType, ime: ImeAction,
    secret: Boolean = false, onIme: () -> Unit,
) {
    OutlinedTextField(
        value, onChange, Modifier.fillMaxWidth(), singleLine = true,
        label = { Text(label) },
        placeholder = placeholder?.let { { CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) { Text(it) } } },
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = type, imeAction = ime, autoCorrectEnabled = false),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onAny = { onIme() }),
        textStyle = androidx.compose.ui.text.TextStyle(textDirection = androidx.compose.ui.text.style.TextDirection.Ltr),
    )
}
