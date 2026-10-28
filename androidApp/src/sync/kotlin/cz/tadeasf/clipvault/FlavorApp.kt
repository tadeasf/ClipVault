package cz.tadeasf.clipvault

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import cz.tadeasf.clipvault.sync.android.SyncFeature

const val FLAVOR_NAME = "sync"

/** Placeholder until step 8; nothing is wired yet. */
fun flavorSyncSection(): (@Composable () -> Unit) = {
    Text("Sync", style = MaterialTheme.typography.titleMedium)
    Text("Sync with your other devices: coming in a later version. (${SyncFeature.status()})")
}
