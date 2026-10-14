package ir.panbeh.app.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import ir.panbeh.app.system.Updater

/** Resumes background sync after a reboot or an app update, if the user had it on; drops the installed update's APK. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val a = intent.action
        if (a == Intent.ACTION_MY_PACKAGE_REPLACED) Updater.apkFile(ctx).delete() // installed: the downloaded APK is done
        if ((a == Intent.ACTION_BOOT_COMPLETED || a == Intent.ACTION_MY_PACKAGE_REPLACED) && SyncControl.isEnabled(ctx)) {
            SyncControl.start(ctx)
        }
    }
}
