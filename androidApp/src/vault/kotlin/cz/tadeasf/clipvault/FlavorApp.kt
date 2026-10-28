package cz.tadeasf.clipvault

import android.app.Application
import android.content.Context
import androidx.activity.ComponentActivity
import cz.tadeasf.clipvault.history.ClipRepository
import cz.tadeasf.clipvault.ui.sync.SyncUi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

// The vault flavor has no sync at all: every hook is a no-op and settings show no sync section.

const val FLAVOR_NAME = "vault"

fun flavorDbDispatcher(): CoroutineDispatcher = Dispatchers.IO

fun flavorRepository(app: Application, repository: ClipRepository): ClipRepository = repository

fun flavorStart(app: Application, graph: AppGraph) = Unit

fun flavorLocalClip(app: Application) = Unit

fun flavorAppVisible(context: Context) = Unit

fun flavorSyncUi(activity: ComponentActivity): SyncUi? = null
