package ir.panbeh.app.ui.lock

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import ir.panbeh.app.PanbehApp
import ir.panbeh.app.R
import ir.panbeh.app.data.AppLock
import ir.panbeh.app.ui.common.MenuItem
import ir.panbeh.app.ui.common.Sheet
import ir.panbeh.app.ui.common.Wallpaper
import ir.panbeh.app.ui.common.toast
import ir.panbeh.app.ui.theme.Panbeh
import ir.panbeh.core.time.num
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

private fun Context.activity(): FragmentActivity? {
    var c: Context? = this
    while (c is ContextWrapper) { if (c is FragmentActivity) return c; c = c.baseContext }
    return null
}

fun biometricAvailable(ctx: Context) = BiometricManager.from(ctx).canAuthenticate(BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS

/** Fingerprint/face prompt; true when it succeeded. */
suspend fun biometricUnlock(ctx: Context): Boolean {
    val act = ctx.activity() ?: return false
    return suspendCancellableCoroutine { cont ->
        val prompt = BiometricPrompt(act, ContextCompat.getMainExecutor(act), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { if (cont.isActive) cont.resume(true) }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) { if (cont.isActive) cont.resume(false) }
        })
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder().setTitle("باز کردن پنبه").setNegativeButtonText("رمز")
                .setAllowedAuthenticators(BIOMETRIC_WEAK).build(),
        )
    }
}

/**
 * Digits by tap. With [len] it ends by itself at that length; otherwise a confirm key once there are MIN.
 * [shake] bumps on a wrong PIN; [extra] sits left of 0 (the fingerprint key).
 */
@Composable
fun PinPad(title: String, sub: String? = null, len: Int? = null, disabled: Boolean = false, shake: Int = 0, badge: Boolean = true, extra: (@Composable () -> Unit)? = null, onDone: (String) -> Unit) {
    var pin by remember { mutableStateOf("") }
    val haptic = LocalHapticFeedback.current
    val dx = remember { Animatable(0f) }
    LaunchedEffect(shake, title) {
        pin = ""
        if (shake > 0) for (x in listOf(-12f, 12f, -8f, 8f, 0f)) dx.animateTo(x, androidx.compose.animation.core.tween(50))
    }
    fun press(k: String) {
        if (disabled || (len != null && pin.length == len)) return // a full PIN is being checked
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        when (k) {
            "⌫" -> pin = pin.dropLast(1)
            "ok" -> if (pin.length >= AppLock.MIN) onDone(pin)
            else -> {
                pin = (pin + k).take(len ?: AppLock.MAX)
                if (len != null && pin.length == len) onDone(pin)
            }
        }
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (badge) Box(Modifier.size(56.dp).clip(CircleShape).background(Panbeh.colors.accent), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Lock, null, tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(28.dp))
        }
        Text(title, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        if (sub != null) Text(sub, color = Panbeh.colors.muted)
        // ltr: digits are entered left to right, like the number itself
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Row(Modifier.offset { IntOffset(dx.value.dp.roundToPx(), 0) }.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                repeat(len ?: maxOf(AppLock.MIN, pin.length)) { i ->
                    Box(Modifier.size(14.dp).clip(CircleShape).background(if (i < pin.length) Panbeh.colors.accent else Panbeh.colors.border))
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                for (row in listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"))) {
                    Row(horizontalArrangement = Arrangement.spacedBy(26.dp)) { row.forEach { k -> Key(num(k.toInt()), disabled) { press(k) } } }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(26.dp)) {
                    if (len != null) Box(Modifier.size(72.dp), contentAlignment = Alignment.Center) { extra?.invoke() }
                    else KeyIcon({ Icon(Icons.Default.Check, "تأیید") }, disabled || pin.length < AppLock.MIN) { press("ok") }
                    Key(num(0), disabled) { press("0") }
                    KeyIcon({ Icon(Icons.AutoMirrored.Filled.Backspace, "پاک کردن") }, disabled || pin.isEmpty()) { press("⌫") }
                }
            }
        }
    }
}

@Composable
private fun Key(label: String, disabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(72.dp).clip(CircleShape).background(Panbeh.colors.hover).clickable(enabled = !disabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, fontSize = 28.sp, color = if (disabled) Panbeh.colors.muted else Panbeh.colors.text) }
}

@Composable
private fun KeyIcon(icon: @Composable () -> Unit, disabled: Boolean, onClick: () -> Unit) {
    Box(Modifier.size(72.dp).clip(CircleShape).clickable(enabled = !disabled, onClick = onClick), contentAlignment = Alignment.Center) { icon() }
}

/** Seconds left to wait after too many wrong tries, ticking down. */
@Composable
private fun rememberWait(): Pair<Int, () -> Unit> {
    var left by remember { mutableLongStateOf(AppLock.waitLeft()) }
    LaunchedEffect(left) { if (left > 0) { delay(1000); left = AppLock.waitLeft() } }
    return ((left + 999) / 1000).toInt() to { left = AppLock.waitLeft() }
}

/** Covers the app until the PIN (or a finger) is given. */
@Composable
fun LockScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val c = remember { AppLock.conf } ?: return // kept: forgetting deletes it while this is still on screen
    var shake by remember { mutableIntStateOf(0) }
    val (wait, rewait) = rememberWait()
    val bio = remember { c.bio && biometricAvailable(ctx) }
    var forgetting by remember { mutableStateOf<String?>(null) }
    val haptic = LocalHapticFeedback.current
    fun tryBio() = scope.launch { if (biometricUnlock(ctx)) AppLock.unlockedByBiometric() }
    LaunchedEffect(Unit) { if (bio) tryBio() }
    BackHandler { ctx.activity()?.moveTaskToBack(true) } // back leaves the app; it doesn't reach what's underneath
    Box(Modifier.fillMaxSize().clickable(enabled = false) {}) {
        Wallpaper(Modifier.fillMaxSize())
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Image(painterResource(R.mipmap.ic_launcher_round), null, Modifier.size(64.dp))
            Spacer(Modifier.height(16.dp))
            if (forgetting != null) {
                Text("رمز را فراموش کرده‌اید؟ قفل برداشته می‌شود و همه‌ی حساب‌ها از این دستگاه خارج می‌شوند. پس از ورود دوباره، پیام‌های رمزنگاری‌شده‌ی قبلی فقط با کلید بازیابی یا تأیید از دستگاهی دیگر خوانده می‌شوند.")
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 16.dp)) {
                    TextButton({ forgetting = null }, enabled = forgetting != "busy") { Text("لغو") }
                    Button({
                        forgetting = "busy"; AppLock.remove(keepLocked = true)
                        scope.launch { runCatching { PanbehApp.instance.accounts.logoutAll() }; AppLock.remove(); PanbehApp.instance.state.boot() }
                    }, enabled = forgetting != "busy", colors = ButtonDefaults.buttonColors(containerColor = Panbeh.colors.danger)) {
                        Text(if (forgetting == "busy") "در حال خروج…" else "خروج از همه‌ی حساب‌ها")
                    }
                }
            } else {
                PinPad("رمز پنبه را وارد کنید", if (wait > 0) "${num(wait)} ثانیه‌ی دیگر دوباره امتحان کنید" else null, c.len, wait > 0, shake, badge = false,
                    extra = if (bio) ({ IconTap(Icons.Default.Fingerprint, "اثر انگشت") { tryBio() } }) else null,
                ) { pin ->
                    scope.launch {
                        if (!AppLock.unlock(pin)) { shake++; rewait(); haptic.performHapticFeedback(HapticFeedbackType.LongPress) }
                    }
                }
                TextButton({ forgetting = "ask" }) { Text("رمز را فراموش کرده‌اید؟", color = Panbeh.colors.muted) }
            }
        }
    }
}

@Composable
private fun IconTap(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, onClick: () -> Unit) {
    Box(Modifier.size(72.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, desc, Modifier.size(32.dp), tint = Panbeh.colors.accent)
    }
}

private val AFTER = listOf(0 to "بلافاصله", 1 to "پس از ۱ دقیقه", 5 to "پس از ۵ دقیقه", 60 to "پس از ۱ ساعت")

/** Settings › قفل برنامه: set a PIN; once there is one, the current PIN first, then timing, fingerprint, change, remove. */
@Composable
fun LockSettings(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var conf by remember { mutableStateOf(AppLock.conf) }
    var step by remember { mutableStateOf(if (conf != null) "verify" else "new") }
    var first by remember { mutableStateOf("") }
    var shake by remember { mutableIntStateOf(0) }
    val (wait, rewait) = rememberWait()
    val bioOk = remember { biometricAvailable(ctx) }
    val haptic = LocalHapticFeedback.current
    fun wrong() { shake++; haptic.performHapticFeedback(HapticFeedbackType.LongPress) }
    Sheet(onClose, "قفل برنامه", fullHeight = true) {
        Column(Modifier.padding(16.dp).align(Alignment.CenterHorizontally), horizontalAlignment = Alignment.CenterHorizontally) {
            when (step) {
                "verify" -> conf?.let { c ->
                    PinPad("رمز فعلی را وارد کنید", if (wait > 0) "${num(wait)} ثانیه‌ی دیگر دوباره امتحان کنید" else null, c.len, wait > 0, shake) { pin ->
                        scope.launch { if (AppLock.checkPin(pin)) step = "options" else { wrong(); rewait() } }
                    }
                }
                "new" -> PinPad("رمز تازه", "${num(AppLock.MIN)} تا ${num(AppLock.MAX)} رقم", shake = shake) { first = it; step = "repeat" }
                "repeat" -> PinPad("رمز را دوباره وارد کنید", len = first.length, shake = shake) { pin ->
                    if (pin != first) { wrong(); step = "new"; toast("دو رمز یکی نبود؛ دوباره") }
                    else scope.launch { AppLock.setPin(pin); conf = AppLock.conf; step = "options"; toast("قفل برنامه روشن شد") }
                }
                "options" -> conf?.let { c ->
                    Text("پنبه با باز شدن و پس از مدتی بیرون بودن از برنامه، رمز می‌خواهد. این قفل صفحه است و داده‌های روی دستگاه را رمزنگاری نمی‌کند.", color = Panbeh.colors.muted)
                    Text("قفل خودکار", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp))
                    AFTER.forEach { (v, label) ->
                        MenuItem(label + if (c.after == v) "  ✓" else "") { AppLock.setOptions(after = v); conf = AppLock.conf }
                    }
                    if (bioOk) Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("باز کردن با اثر انگشت یا چهره", Modifier.weight(1f))
                        Switch(c.bio, { AppLock.setOptions(bio = it); conf = AppLock.conf })
                    }
                    MenuItem("تغییر رمز", Icons.Default.Lock) { step = "new" }
                    MenuItem("خاموش کردن قفل", danger = true) { AppLock.remove(); onClose(); toast("قفل برنامه خاموش شد") }
                }
            }
        }
    }
}
