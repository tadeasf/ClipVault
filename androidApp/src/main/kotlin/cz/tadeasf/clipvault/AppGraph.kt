package cz.tadeasf.clipvault

import android.app.Application
import cz.tadeasf.clipvault.capture.CaptureNotification
import cz.tadeasf.clipvault.capture.RetentionAlarm
import cz.tadeasf.clipvault.db.ClipDatabase
import cz.tadeasf.clipvault.history.ClipIngest
import cz.tadeasf.clipvault.history.IngestResult
import cz.tadeasf.clipvault.history.SqlClipRepository
import cz.tadeasf.clipvault.model.ClipHints
import cz.tadeasf.clipvault.security.KeystoreDbKeyProvider
import cz.tadeasf.clipvault.security.keyFor
import cz.tadeasf.clipvault.settings.SqlSettingsRepository
import cz.tadeasf.clipvault.settings.deviceId
import cz.tadeasf.clipvault.storage.AndroidDriverFactory
import cz.tadeasf.clipvault.storage.clipDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch

/** Process-wide objects, created on first use. Every capture path and the UI share one database. */
class AppGraph(private val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val io = Dispatchers.IO

    val db: ClipDatabase by lazy {
        val drivers = AndroidDriverFactory(app)
        clipDatabase(drivers.open(KeystoreDbKeyProvider(app).keyFor(drivers.exists())))
    }
    val settings by lazy { SqlSettingsRepository(db, io) }
    val repository by lazy { SqlClipRepository(db, deviceId(db), System::currentTimeMillis, io) }
    val ingest by lazy { ClipIngest(repository) { it.sourcePackage in settings.settings.value.excludedPackages } }

    /** Capture entry used by every path: ingest, then housekeeping. */
    suspend fun capture(text: String, hints: ClipHints): IngestResult {
        val result = ingest.ingest(text, hints)
        if (result is IngestResult.Stored) {
            settings.update { it.copy(lastCaptureAt = System.currentTimeMillis()) }
            if (result.sensitive) RetentionAlarm.schedule(app, settings.settings.value.sensitiveTtlSeconds)
        }
        applyRetention()
        return result
    }

    suspend fun applyRetention() = repository.applyRetention(settings.settings.value.retentionPolicy())

    /** Keeps the capture notification in line with the setting, now and on every change. */
    fun start() = scope.launch(io) {
        applyRetention()
        settings.settings.distinctUntilChangedBy { it.captureNotification }.collect {
            CaptureNotification.sync(app, it.captureNotification)
        }
    }
}

class ClipVaultApplication : Application() {
    val graph by lazy { AppGraph(this) }

    override fun onCreate() {
        super.onCreate()
        CaptureNotification.createChannel(this)
        graph.start()
    }
}

val android.content.Context.graph: AppGraph get() = (applicationContext as ClipVaultApplication).graph
