package cz.tadeasf.clipvault.capture

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import cz.tadeasf.clipvault.MainActivity
import cz.tadeasf.clipvault.R
import cz.tadeasf.clipvault.graph

/** The optional, quiet notification with a "Capture" action. Not a foreground service. */
object CaptureNotification {
    private const val CHANNEL = "capture"
    private const val ID = 1

    fun createChannel(context: Context) {
        val channel = NotificationChannel(CHANNEL, "Capture", NotificationManager.IMPORTANCE_LOW)
        channel.description = "Shortcut to capture the clipboard"
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun sync(context: Context, enabled: Boolean) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (enabled && canPost(context)) manager.notify(ID, build(context)) else manager.cancel(ID)
    }

    fun canPost(context: Context): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun build(context: Context): Notification {
        val capture = PendingIntent.getActivity(context, 1, CaptureActivity.intent(context), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(context, 2, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val action = Notification.Action.Builder(null, "Capture clipboard", capture).build()
        return Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle("ClipVault")
            .setContentText("Copy something, then tap Capture")
            .setContentIntent(open)
            .addAction(action)
            .setOngoing(true)
            .setShowWhen(false)
            .build()
    }
}

/** Re-posts the capture notification after a reboot when it is enabled. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        CaptureNotification.sync(context, context.graph.settings.settings.value.captureNotification)
    }
}
