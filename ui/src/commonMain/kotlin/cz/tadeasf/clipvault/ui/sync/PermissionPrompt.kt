package cz.tadeasf.clipvault.ui.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** An in-UI explanation shown before the system dialog; [denied] once the user said no. */
data class PermissionPrompt(val permission: SyncPermission, val denied: Boolean = false)

/** Explain first, then ask; a denial stays on screen with a way to the app's system settings. */
class PermissionAsker(private val gate: PermissionGate, private val scope: CoroutineScope) {
    private val current = MutableStateFlow<PermissionPrompt?>(null)
    val prompt: StateFlow<PermissionPrompt?> = current
    private var onGranted: suspend () -> Unit = {}
    private var request: Job? = null

    fun ask(permission: SyncPermission, onGranted: suspend () -> Unit) {
        if (gate.isGranted(permission)) {
            scope.launch { onGranted() }
            return
        }
        this.onGranted = onGranted
        current.value = PermissionPrompt(permission)
    }

    fun proceed() {
        val prompt = current.value?.takeUnless { it.denied } ?: return
        if (request?.isActive == true) return
        request = scope.launch {
            if (gate.request(prompt.permission)) {
                current.value = null
                onGranted()
            } else {
                current.value = prompt.copy(denied = true)
            }
        }
    }

    fun dismiss() {
        current.value = null
    }

    fun openAppSettings() = gate.openAppSettings()
}

@Composable
internal fun PermissionNotice(prompt: PermissionPrompt, proceed: () -> Unit, dismiss: () -> Unit, openSettings: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            if (prompt.denied) prompt.permission.deniedText() else prompt.permission.explanation(),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag("permissionNotice"),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (prompt.denied) {
                OutlinedButton(onClick = openSettings) { Text("Open app settings") }
            } else {
                Button(onClick = proceed, modifier = Modifier.testTag("permissionContinue")) { Text("Continue") }
            }
            TextButton(onClick = dismiss, modifier = Modifier.testTag("permissionDismiss")) { Text("Not now") }
        }
    }
}

private fun SyncPermission.explanation(): String = when (this) {
    SyncPermission.LOCAL_NETWORK ->
        "ClipVault finds and talks to your paired devices on this Wi-Fi network only. Android asks for local " +
            "network access for that. Nothing goes to the internet."
    SyncPermission.LOCATION ->
        "Android only tells apps the Wi-Fi network name with location permission. ClipVault uses it only to " +
            "check the name against this list; it never reads or stores your location."
    SyncPermission.CAMERA -> "ClipVault uses the camera only to read the pairing QR code. Nothing is recorded."
}

private fun SyncPermission.deniedText(): String = when (this) {
    SyncPermission.LOCAL_NETWORK -> "Sync needs local network access, so it stays off. You can allow it in the app's settings."
    SyncPermission.LOCATION ->
        "The Wi-Fi allowlist needs location permission to read the network name, so it stays off. " +
            "You can allow it in the app's settings."
    SyncPermission.CAMERA -> "Scanning needs the camera. You can allow it in the app's settings, or pair with a 6-digit code."
}
