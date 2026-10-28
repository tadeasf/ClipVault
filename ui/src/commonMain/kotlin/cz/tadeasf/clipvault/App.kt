package cz.tadeasf.clipvault

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview

/** Placeholder shell until step 3/4. [syncStatus] comes from the host app so :ui never sees sync code. */
@Composable
@Preview
fun App(syncStatus: String = "Sync not included in this build") {
    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.safeContentPadding().fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(appTitle(), style = MaterialTheme.typography.headlineSmall)
                Text(syncStatus, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
