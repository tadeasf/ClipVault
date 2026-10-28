package cz.tadeasf.clipvault.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import cz.tadeasf.clipvault.model.Clip
import cz.tadeasf.clipvault.model.ContentType
import cz.tadeasf.clipvault.transform.transformations
import cz.tadeasf.clipvault.ui.label
import cz.tadeasf.clipvault.ui.relativeTime
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@Composable
fun DetailScreen(viewModel: DetailViewModel, back: () -> Unit) {
    val state by viewModel.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val message = (state as? DetailState.Loaded)?.message
    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); viewModel.messageShown() }
    }
    LaunchedEffect(state) { if (state == DetailState.Gone) back() }
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, modifier = Modifier.safeDrawingPadding()) { padding ->
        Column(Modifier.padding(padding).padding(16.dp).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = back) { Text("← History") }
            (state as? DetailState.Loaded)?.let { Loaded(it, viewModel) }
        }
    }
}

@Composable
private fun Loaded(state: DetailState.Loaded, viewModel: DetailViewModel) {
    val clip = state.clip
    Metadata(clip)
    Actions(state, viewModel)
    TransformMenu(viewModel)
    val mono = clip.contentType == ContentType.CODE || clip.contentType == ContentType.JSON
    SelectionContainer(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        Text(clip.content, fontFamily = if (mono) FontFamily.Monospace else null, modifier = Modifier.testTag("content"))
    }
}

@OptIn(ExperimentalTime::class)
@Composable
private fun Metadata(clip: Clip) {
    val now = Clock.System.now().toEpochMilliseconds()
    val lines = listOfNotNull(
        "${clip.contentType.label()} · ${relativeTime(now, clip.createdAt)}",
        clip.categories.takeIf { it.isNotEmpty() }?.let { "Sensitive: " + it.joinToString { c -> c.name.lowercase() } },
        clip.sourcePackage?.let { "From $it" },
    )
    lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
}

@Composable
private fun Actions(state: DetailState.Loaded, viewModel: DetailViewModel) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = viewModel::copy) { Text("Copy") }
        OutlinedButton(onClick = viewModel::togglePin, modifier = Modifier.testTag("pin")) {
            Text(if (state.clip.pinned) "Unpin" else "Pin")
        }
        OutlinedButton(onClick = viewModel::share) { Text("Share") }
        OutlinedButton(onClick = viewModel::delete, modifier = Modifier.testTag("delete")) { Text("Delete") }
    }
    if (state.pinWarning) {
        Text(
            "Sensitive clip: pinning keeps it past the automatic expiry. It stays hidden in the list.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag("pinWarning"),
        )
    }
}

@Composable
private fun TransformMenu(viewModel: DetailViewModel) {
    var open by remember { mutableStateOf(false) }
    var saveAsNew by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box {
            OutlinedButton(onClick = { open = true }) { Text("Transform…") }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                transformations.forEach { transformation ->
                    DropdownMenuItem(
                        text = { Text(transformation.label()) },
                        onClick = { open = false; viewModel.apply(transformation, saveAsNew) },
                    )
                }
            }
        }
        Checkbox(checked = saveAsNew, onCheckedChange = { saveAsNew = it })
        Text("also save as new clip")
    }
}
