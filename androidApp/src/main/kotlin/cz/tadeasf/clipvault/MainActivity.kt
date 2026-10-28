package cz.tadeasf.clipvault

import android.Manifest
import android.os.Build
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.lifecycleScope
import cz.tadeasf.clipvault.capture.CaptureNotification
import cz.tadeasf.clipvault.settings.ThemeMode
import cz.tadeasf.clipvault.ui.AppEnvironment
import cz.tadeasf.clipvault.ui.ClipVaultApp
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        CaptureNotification.sync(this, graph.settings.settings.value.captureNotification)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val env = AppEnvironment(graph.repository, graph.ingest, graph.settings, AndroidAppPlatform(this, FLAVOR_NAME))
        setContent {
            SystemBarsFollowTheme()
            ClipVaultApp(env, sourceIcon = { SourceAppIcon(it) }, syncSection = flavorSyncSection())
        }
        lifecycleScope.launch { requestNotificationPermissionWhenEnabled() }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch { graph.applyRetention() } // expired sensitive clips never show up in the list
    }

    /** The capture notification needs POST_NOTIFICATIONS on Android 13+; ask when the user turns it on. */
    private suspend fun requestNotificationPermissionWhenEnabled() {
        graph.settings.settings.collectLatest {
            if (it.captureNotification && !CaptureNotification.canPost(this) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}

/** enableEdgeToEdge() follows the system dark mode; with the app's own theme the status-bar icons must follow it too. */
@Composable
private fun ComponentActivity.SystemBarsFollowTheme() {
    val mode by graph.settings.settings.collectAsState()
    val dark = when (mode.theme) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    LaunchedEffect(dark) {
        val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    }
}

@Composable
private fun SourceAppIcon(packageName: String) {
    val context = LocalContext.current
    val bitmap = remember(packageName) {
        runCatching { context.packageManager.getApplicationIcon(packageName).toBitmap(48, 48).asImageBitmap() }.getOrNull()
    } ?: return
    Image(bitmap, contentDescription = packageName, modifier = Modifier.size(24.dp))
}
