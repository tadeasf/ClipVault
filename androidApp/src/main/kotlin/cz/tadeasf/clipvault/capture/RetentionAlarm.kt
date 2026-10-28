package cz.tadeasf.clipvault.capture

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import cz.tadeasf.clipvault.graph
import kotlinx.coroutines.launch

/**
 * Runs retention (incl. the sensitive-clip TTL sweep) shortly after a sensitive clip is stored, even if the
 * app is closed. Inexact on purpose: no SCHEDULE_EXACT_ALARM permission, and WorkManager would pull in
 * ACCESS_NETWORK_STATE, which the vault flavor must not declare. Retention also runs on every capture and start.
 */
object RetentionAlarm {
    private const val SLACK_MILLIS = 5_000L

    fun schedule(context: Context, afterSeconds: Long) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val trigger = SystemClock.elapsedRealtime() + afterSeconds * 1000 + SLACK_MILLIS
        alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME, trigger, pendingIntent(context))
    }

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 0, Intent(context, RetentionReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

class RetentionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        context.graph.scope.launch {
            try {
                context.graph.applyRetention()
            } finally {
                pending.finish()
            }
        }
    }
}
