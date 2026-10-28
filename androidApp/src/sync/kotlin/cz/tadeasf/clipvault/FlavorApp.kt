package cz.tadeasf.clipvault

import android.app.Application
import android.content.Context
import androidx.activity.ComponentActivity
import cz.tadeasf.clipvault.history.ClipRepository
import cz.tadeasf.clipvault.storage.AndroidDriverFactory
import cz.tadeasf.clipvault.sync.android.SyncDispatcher
import cz.tadeasf.clipvault.sync.android.SyncRuntime
import cz.tadeasf.clipvault.sync.android.SyncedClipRepository
import cz.tadeasf.clipvault.sync.db.SyncDatabase
import cz.tadeasf.clipvault.ui.sync.SyncUi
import kotlinx.coroutines.CoroutineDispatcher

const val FLAVOR_NAME = "sync"

/** The store and the sync node share one thread (SyncDispatcher). */
fun flavorDbDispatcher(): CoroutineDispatcher = SyncDispatcher.db

fun flavorRepository(app: Application, repository: ClipRepository): ClipRepository =
    SyncedClipRepository(repository) { SyncRuntime.of(app) }

/** Builds the runtime at process start, so the screen on/off policy and the catch-up worker have it. */
fun flavorStart(app: Application, graph: AppGraph) {
    SyncRuntime.provide { context ->
        val syncDb = SyncDatabase(AndroidDriverFactory(context, "sync.db", SyncDatabase.Schema).open(graph.dbKey))
        SyncRuntime(context, graph.db, syncDb, excluded = { graph.settings.settings.value.excludedPackages })
    }
    SyncRuntime.of(app)
}

fun flavorLocalClip(app: Application) {
    SyncRuntime.of(app)?.localClip()
}

fun flavorAppVisible(context: Context) {
    SyncRuntime.of(context)?.appVisible()
}

/** Call from onCreate: the permission gate registers its activity-result launcher there. */
fun flavorSyncUi(activity: ComponentActivity): SyncUi? = SyncRuntime.of(activity)?.let { runtime ->
    SyncUi(runtime, ActivityPermissionGate(activity)) { modifier, onText -> QrScanner(modifier, onText) }
}
