package cz.tadeasf.clipvault.ui.sync

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import cz.tadeasf.clipvault.detect.SensitiveCategory
import cz.tadeasf.clipvault.ui.relativeTime
import cz.tadeasf.clipvault.ui.settings.Section
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@Composable
fun SyncSettingsSection(viewModel: SyncSettingsViewModel, openPairing: () -> Unit) {
    val state by viewModel.state.collectAsState()
    val prompt by viewModel.prompt.collectAsState()
    val notice: @Composable (SyncPermission) -> Unit = { permission ->
        prompt?.takeIf { it.permission == permission }?.let {
            PermissionNotice(it, viewModel::continuePermission, viewModel::dismissPermission, viewModel::openAppSettings)
        }
    }
    Section("Sync")
    ToggleRow("Sync with paired devices", state.enabled, "syncEnabled", Role.Switch, viewModel::setEnabled)
    notice(SyncPermission.LOCAL_NETWORK)
    Text(state.statusLine, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("syncStatus"))
    DeviceName(state.deviceName, viewModel::setDeviceName)
    if (!state.enabled) return
    Peers(state.peers, viewModel::unpair)
    OutlinedButton(onClick = openPairing, modifier = Modifier.testTag("pairDevice")) { Text("Pair a device") }
    SensitiveOptIns(state.sensitiveOptIn, viewModel::setSensitiveOptIn)
    ToggleRow("Sync only on these Wi-Fi networks", state.wifiAllowlist, "wifiAllowlist", Role.Switch, viewModel::setWifiAllowlist)
    notice(SyncPermission.LOCATION)
    if (state.wifiAllowlist) Allowlist(state, viewModel)
}

/** Saves on IME done and on focus loss; a blank name snaps back to the saved one. */
@Composable
private fun DeviceName(name: String, save: (String) -> Unit) {
    var text by remember(name) { mutableStateOf(name) }
    val focus = LocalFocusManager.current
    val commit = { if (text.isBlank()) text = name else save(text) }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        label = { Text("This device's name") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { commit(); focus.clearFocus() }),
        modifier = Modifier.fillMaxWidth().testTag("syncName").onFocusChanged { if (!it.isFocused) commit() },
    )
}

@OptIn(ExperimentalTime::class)
@Composable
private fun Peers(peers: List<PeerRow>, unpair: (String) -> Unit) {
    var confirming by remember { mutableStateOf<PeerRow?>(null) }
    Text("Paired devices", style = MaterialTheme.typography.titleSmall)
    if (peers.isEmpty()) Text("No paired devices yet. Pair one below.", style = MaterialTheme.typography.bodySmall)
    val now = Clock.System.now().toEpochMilliseconds()
    peers.forEach { peer ->
        Row(Modifier.fillMaxWidth().testTag("peer"), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(peer.name)
                val seen = peer.lastSeen?.let { "last seen ${relativeTime(now, it)}" } ?: "never connected"
                Text("${peer.state} · $seen", style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = { confirming = peer }) { Text("Unpair") }
        }
    }
    confirming?.let { peer ->
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text("Unpair ${peer.name}?") },
            text = { Text("The two devices stop syncing. Pair again to resume.") },
            confirmButton = {
                TextButton(onClick = { unpair(peer.id); confirming = null }, modifier = Modifier.testTag("unpairConfirm")) { Text("Unpair") }
            },
            dismissButton = { TextButton(onClick = { confirming = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SensitiveOptIns(optedIn: Set<SensitiveCategory>, set: (SensitiveCategory, Boolean) -> Unit) {
    Text("Receive sensitive clips", style = MaterialTheme.typography.titleSmall)
    Text(
        "Sensitive clips are never synced unless you opt in here. Opted-in categories arrive from your other " +
            "devices, stay hidden and expire like sensitive clips copied on this phone.",
        style = MaterialTheme.typography.bodySmall,
    )
    SensitiveCategory.entries.filter { it != SensitiveCategory.UNKNOWN }.forEach { category ->
        ToggleRow(category.label(), category in optedIn, "optIn_${category.name}", Role.Checkbox) { set(category, it) }
    }
}

@Composable
private fun Allowlist(state: SyncSnapshot, viewModel: SyncSettingsViewModel) {
    if (state.allowedSsids.isEmpty()) Text("No networks yet: sync stays paused until you add one.", style = MaterialTheme.typography.bodySmall)
    state.allowedSsids.sorted().forEach { ssid ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(ssid, modifier = Modifier.weight(1f))
            TextButton(onClick = { viewModel.removeSsid(ssid) }) { Text("Remove") }
        }
    }
    state.currentSsid?.takeIf { it !in state.allowedSsids }?.let { ssid ->
        OutlinedButton(onClick = { viewModel.addSsid(ssid) }, modifier = Modifier.testTag("addCurrentSsid")) { Text("Add $ssid") }
    }
    var manual by remember { mutableStateOf("") }
    val add = { viewModel.addSsid(manual); manual = "" }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = manual,
            onValueChange = { manual = it },
            label = { Text("Network name") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { add() }),
            modifier = Modifier.weight(1f).testTag("ssidField"),
        )
        TextButton(onClick = add, enabled = manual.isNotBlank()) { Text("Add") }
    }
}

/** The whole row toggles: bigger target, and one node for screen readers. */
@Composable
private fun ToggleRow(label: String, checked: Boolean, tag: String, role: Role, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(checked, role = role, onValueChange = onChange).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (role == Role.Checkbox) Checkbox(checked = checked, onCheckedChange = null)
        Text(label, modifier = Modifier.weight(1f))
        if (role == Role.Switch) Switch(checked = checked, onCheckedChange = null)
    }
}

private fun SensitiveCategory.label(): String = when (this) {
    SensitiveCategory.PASSWORD -> "Passwords"
    SensitiveCategory.OTP -> "One-time codes"
    SensitiveCategory.CARD_NUMBER -> "Card numbers"
    SensitiveCategory.IBAN -> "IBANs"
    SensitiveCategory.API_KEY -> "API keys"
    SensitiveCategory.PRIVATE_KEY -> "Private keys"
    SensitiveCategory.CREDENTIALS_PAIR -> "Username and password pairs"
    SensitiveCategory.SSN_LIKE_ID -> "National ID numbers"
    SensitiveCategory.UNKNOWN -> "Other"
}
