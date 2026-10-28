package cz.tadeasf.clipvault.sync.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Holds the live connection (design 8.1: the only foreground service, sync flavor only). Type `specialUse`,
 * which Android 15/16 put no runtime cap on (dataSync gets 6 h per 24 h). [KeepAlive] decides when it runs;
 * this only holds the node while it does.
 */
class SyncService : Service() {
    override fun onCreate() {
        super.onCreate()
        val notification = SyncNotifications.running(this)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(SyncNotifications.SERVICE_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(SyncNotifications.SERVICE_ID, notification)
        }
        SyncRuntime.of(this)?.hold(HOLDER, true) ?: stopSelf()
    }

    // Not sticky: a restart from the background would be refused anyway; WorkManager covers the gap.
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onDestroy() {
        SyncRuntime.of(this)?.hold(HOLDER, false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val HOLDER = "service"

        fun intent(context: Context) = Intent(context, SyncService::class.java)
    }
}

/** Catch-up while the service is stopped: every 15 minutes, and expedited after a local clip (WorkManager). */
object CatchUp {
    const val PERIODIC = "clipvault-sync-catchup"
    const val NOW = "clipvault-sync-now"

    private val connected = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun schedulePeriodic(context: Context) = WorkManager.getInstance(context).enqueueUniquePeriodicWork(
        PERIODIC,
        ExistingPeriodicWorkPolicy.KEEP,
        PeriodicWorkRequestBuilder<CatchUpWorker>(15, TimeUnit.MINUTES).setConstraints(connected).build(),
    )

    fun expedite(context: Context) = WorkManager.getInstance(context).enqueueUniqueWork(
        NOW,
        ExistingWorkPolicy.KEEP,
        OneTimeWorkRequestBuilder<CatchUpWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setConstraints(connected)
            .build(),
    )

    fun cancel(context: Context) = WorkManager.getInstance(context).run {
        cancelUniqueWork(PERIODIC)
        cancelUniqueWork(NOW)
    }
}

class CatchUpWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        SyncRuntime.of(applicationContext)?.catchUp()
        return Result.success()
    }

    // Expedited work runs as a foreground service below Android 12; untested there (the dev phone is on 16).
    override suspend fun getForegroundInfo() = ForegroundInfo(SyncNotifications.WORK_ID, SyncNotifications.running(applicationContext))
}

object SyncNotifications {
    const val SERVICE_ID = 0x5c01
    const val WORK_ID = 0x5c02
    private const val CHANNEL = "sync"

    fun running(context: Context): Notification {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Sync", NotificationManager.IMPORTANCE_MIN))
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.let { PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE) }
        return Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("ClipVault sync")
            .setContentText("Connected to your devices on this network")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }
}
