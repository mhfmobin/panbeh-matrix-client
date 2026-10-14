package ir.panbeh.app.system

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Phone settings and permissions the Settings screens ask about. */
object Device {
    /** BIOMETRIC_WEAK: face unlock on most phones counts too; this only lifts a UI lock (no key is released). */
    private const val BIO = BiometricManager.Authenticators.BIOMETRIC_WEAK

    // ---------- network ----------

    /** Mobile data or a metered Wi-Fi: the media auto-download rules for that network apply. */
    fun isMetered(ctx: Context): Boolean = ctx.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered == true

    // ---------- biometrics ----------

    fun biometricAvailable(ctx: Context): Boolean =
        BiometricManager.from(ctx).canAuthenticate(BIO) == BiometricManager.BIOMETRIC_SUCCESS

    /**
     * Shows the system prompt; true when it succeeded, false when cancelled, failed out, or [cancel] ("use PIN") was pressed.
     * BiometricPrompt needs a FragmentActivity: MainActivity must extend it.
     */
    suspend fun biometricUnlock(activity: FragmentActivity, title: String = "باز کردن پنبه", cancel: String = "ورود رمز"): Boolean =
        suspendCancellableCoroutine { cont ->
            activity.runOnUiThread {
                val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { if (cont.isActive) cont.resume(true) }
                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) { if (cont.isActive) cont.resume(false) }
                    // onAuthenticationFailed: one wrong finger; the prompt stays up for another try
                })
                prompt.authenticate(BiometricPrompt.PromptInfo.Builder().setTitle(title).setNegativeButtonText(cancel).setAllowedAuthenticators(BIO).build())
                cont.invokeOnCancellation { activity.runOnUiThread { prompt.cancelAuthentication() } }
            }
        }

    // ---------- notifications ----------

    /** Notifications are allowed (permission on Android 13+, and not switched off in settings). */
    fun notificationsAllowed(ctx: Context): Boolean = NotificationManagerCompat.from(ctx).areNotificationsEnabled()

    /** "granted" | "denied" | "default" like the web app's status: "default" = Android 13+ and never asked. */
    fun notificationPermission(ctx: Context): String = when {
        notificationsAllowed(ctx) -> "granted"
        Build.VERSION.SDK_INT >= 33 && ctx.getSharedPreferences("panbeh", Context.MODE_PRIVATE).getBoolean("notifyAsked", false) -> "denied"
        Build.VERSION.SDK_INT >= 33 -> "default"
        else -> "denied"
    }

    /** Asks for POST_NOTIFICATIONS (Android 13+; before that nothing to ask). Returns whether notifications are allowed now. */
    suspend fun requestNotificationPermission(activity: ComponentActivity): Boolean {
        if (Build.VERSION.SDK_INT < 33 || notificationsAllowed(activity)) return notificationsAllowed(activity)
        val granted = suspendCancellableCoroutine<Boolean> { cont ->
            val launcher = activity.activityResultRegistry.register("panbeh.notifyPermission", ActivityResultContracts.RequestPermission()) { ok ->
                if (cont.isActive) cont.resume(ok)
            }
            cont.invokeOnCancellation { launcher.unregister() }
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        activity.getSharedPreferences("panbeh", Context.MODE_PRIVATE).edit().putBoolean("notifyAsked", true).apply()
        return granted && notificationsAllowed(activity)
    }

    /** Android's notification settings for the app: each channel's sound, vibration and lock-screen behaviour. */
    fun openNotifySettings(ctx: Context) {
        ctx.startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    // ---------- battery ----------

    /** True when Doze may cut our network in the background (we aren't exempt). */
    fun batteryOptimized(ctx: Context): Boolean = !ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)

    /** Opens the system dialog that lets us run unrestricted in the background. */
    fun requestBatteryExemption(ctx: Context) {
        if (!batteryOptimized(ctx)) return
        try {
            ctx.startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: RuntimeException) {
            ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    // ---------- full-screen calls ----------

    /** Android 14+: full-screen ringing needs the user's OK unless the store granted it. */
    fun canUseFullScreenIntent(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 34 || ctx.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()

    fun requestFullScreenIntent(ctx: Context) {
        if (Build.VERSION.SDK_INT < 34) return
        try {
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: RuntimeException) {
            openNotifySettings(ctx)
        }
    }
}
