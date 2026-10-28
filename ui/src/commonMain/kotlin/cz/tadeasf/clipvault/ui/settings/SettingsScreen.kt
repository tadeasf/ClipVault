package cz.tadeasf.clipvault.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import cz.tadeasf.clipvault.settings.ThemeMode

@Composable
fun SettingsScreen(viewModel: SettingsViewModel, appInfo: String, syncSection: (@Composable () -> Unit)?, back: () -> Unit) {
    val state by viewModel.state.collectAsState()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    val loaded = state as? SettingsState.Loaded ?: return
    Column(
        Modifier.safeDrawingPadding().fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TextButton(onClick = back) { Text("← History") }
        Retention(loaded, viewModel)
        HorizontalDivider()
        Capture(loaded, viewModel)
        HorizontalDivider()
        Theme(loaded.settings.theme, viewModel::setTheme)
        syncSection?.let { HorizontalDivider(); it() }
        HorizontalDivider()
        Section("About")
        Text(appInfo, modifier = Modifier.testTag("about"))
    }
}

@Composable
private fun Retention(state: SettingsState.Loaded, viewModel: SettingsViewModel) {
    val settings = state.settings
    Section("History")
    NumberField("Keep at most (clips, empty = no limit)", settings.maxCount, "maxCount", viewModel::setMaxCount)
    NumberField("Delete after (days, empty = never)", settings.maxAgeDays, "maxAgeDays", viewModel::setMaxAgeDays)
    NumberField("Sensitive clips expire after (seconds)", settings.sensitiveTtlSeconds, "ttl") {
        it?.let(viewModel::setSensitiveTtlSeconds)
    }
    Text("Pinned clips are never deleted automatically.", style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun Capture(state: SettingsState.Loaded, viewModel: SettingsViewModel) {
    Section("Capture")
    SwitchRow("Capture notification (a quiet notification with a Capture button)", state.settings.captureNotification, "notification") {
        viewModel.setCaptureNotification(it)
    }
    state.accessibilityEnabled?.let { enabled -> Accessibility(enabled, viewModel::openAccessibilitySettings) }
    if (state.exclusionsAvailable) Exclusions(state, viewModel)
}

@Composable
private fun Accessibility(enabled: Boolean, openSettings: () -> Unit) {
    Text("Automatic capture (Accessibility): ${if (enabled) "on" else "off"}", style = MaterialTheme.typography.titleSmall)
    Text(
        "Android only lets the app on screen read the clipboard. With this on, ClipVault watches for copy actions " +
            "(it does not read screen content) and briefly opens an invisible window to read the clip. Android 12+ shows " +
            "a \"ClipVault pasted from your clipboard\" message every time. It is the only way to know which app a clip " +
            "came from, so per-app exclusions and source icons need it.",
        style = MaterialTheme.typography.bodySmall,
    )
    OutlinedButton(onClick = openSettings) { Text(if (enabled) "Turn off in system settings" else "Turn on in system settings") }
}

@Composable
private fun Exclusions(state: SettingsState.Loaded, viewModel: SettingsViewModel) {
    var expanded by remember { mutableStateOf(false) }
    val excluded = state.settings.excludedPackages
    TextButton(onClick = { expanded = !expanded }) { Text("Never capture from… (${excluded.size} excluded)") }
    if (!expanded) return
    state.apps.forEach { app ->
        val checked = app.packageName in excluded
        // The whole row toggles, not just the box: bigger target, and one node for screen readers.
        Row(
            Modifier.fillMaxWidth().toggleable(checked, role = Role.Checkbox) { viewModel.setExcluded(app.packageName, it) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = checked, onCheckedChange = null)
            Text(app.label)
        }
    }
}

@Composable
private fun Theme(current: ThemeMode, select: (ThemeMode) -> Unit) {
    Section("Theme")
    ThemeMode.entries.forEach { mode ->
        Row(
            Modifier.fillMaxWidth().selectable(mode == current, role = Role.RadioButton) { select(mode) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = mode == current, onClick = null)
            Text(mode.name.lowercase().replaceFirstChar(Char::uppercase))
        }
    }
}

@Composable
internal fun Section(title: String) = Text(title, style = MaterialTheme.typography.titleMedium)

@Composable
private fun SwitchRow(label: String, checked: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, modifier = Modifier.testTag(tag))
    }
}

/** Saves on every valid edit; an empty field means "no limit" (null). */
@Composable
private fun NumberField(label: String, value: Long?, tag: String, onChange: (Long?) -> Unit) {
    var text by remember(value) { mutableStateOf(value?.toString() ?: "") }
    OutlinedTextField(
        value = text,
        onValueChange = { input ->
            text = input.filter(Char::isDigit)
            onChange(text.toLongOrNull())
        },
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag(tag),
    )
}
