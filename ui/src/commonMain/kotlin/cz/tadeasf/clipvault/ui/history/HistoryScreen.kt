package cz.tadeasf.clipvault.ui.history

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.launch
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import cz.tadeasf.clipvault.model.Clip
import cz.tadeasf.clipvault.model.ClipFilter
import cz.tadeasf.clipvault.model.ContentType
import cz.tadeasf.clipvault.ui.Platform
import cz.tadeasf.clipvault.ui.glyph
import cz.tadeasf.clipvault.ui.label
import cz.tadeasf.clipvault.ui.preview
import cz.tadeasf.clipvault.ui.relativeTime
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** [navigate] with an id opens that clip; with null opens settings. */
@Composable
fun HistoryScreen(
    viewModel: HistoryViewModel,
    platform: Platform,
    sourceIcon: @Composable (String) -> Unit,
    navigate: (String?) -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val message = (state as? HistoryState.Content)?.message
    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); viewModel.messageShown() }
    }
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, modifier = Modifier.safeDrawingPadding()) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Header(onSettings = { navigate(null) })
            when (val current = state) {
                HistoryState.Loading -> Unit
                HistoryState.Empty -> EmptyState(platform.requestAddTile)
                is HistoryState.Content -> HistoryContent(viewModel, current, sourceIcon, navigate)
            }
        }
    }
}

@Composable
private fun Header(onSettings: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(16.dp, 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("ClipVault", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        TextButton(onClick = onSettings) { Text("Settings") }
    }
}

@Composable
private fun HistoryContent(
    viewModel: HistoryViewModel,
    state: HistoryState.Content,
    sourceIcon: @Composable (String) -> Unit,
    navigate: (String?) -> Unit,
) {
    OutlinedTextField(
        value = state.filter.text,
        onValueChange = viewModel::onSearch,
        placeholder = { Text("Search") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("search"),
    )
    Filters(state, viewModel)
    ClipList(viewModel, sourceIcon, open = navigate)
}

@Composable
private fun Filters(state: HistoryState.Content, viewModel: HistoryViewModel) {
    val filter = state.filter
    LazyRow(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.sensitiveCount > 0 || filter.includeSensitive) {
            item { SensitiveChip(filter, state.sensitiveCount, viewModel) }
        }
        item { FilterChip(filter.pinnedOnly, { viewModel.onPinnedOnly(!filter.pinnedOnly) }, { Text("📌 Pinned") }) }
        items(ContentType.entries) { type ->
            val selected = filter.type == type
            FilterChip(selected, { viewModel.onTypeFilter(if (selected) null else type) }, { Text("${type.glyph()} ${type.label()}") })
        }
    }
}

@Composable
private fun SensitiveChip(filter: ClipFilter, count: Long, viewModel: HistoryViewModel) {
    val shown = filter.includeSensitive
    FilterChip(
        selected = shown,
        onClick = { if (shown) viewModel.hideSensitive() else viewModel.showSensitive() },
        label = { Text(if (shown) "Hide sensitive" else "Show sensitive ($count)") },
        modifier = Modifier.testTag("sensitiveToggle"),
    )
}

@Composable
private fun ClipList(viewModel: HistoryViewModel, sourceIcon: @Composable (String) -> Unit, open: (String) -> Unit) {
    val clips = viewModel.clips.collectAsLazyPagingItems()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // Clips captured while we were in the background land above the old scroll anchor; show them.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { scope.launch { listState.scrollToItem(0) } }
    LazyColumn(Modifier.fillMaxSize().testTag("clipList"), state = listState) {
        items(count = clips.itemCount, key = clips.itemKey { it.id }) { index ->
            val clip = clips[index] ?: return@items
            SwipeToDelete(onDelete = { viewModel.delete(clip.id) }) {
                ClipRow(clip, sourceIcon, onClick = { viewModel.copy(clip) }, onLongClick = { open(clip.id) })
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun SwipeToDelete(onDelete: () -> Unit, content: @Composable () -> Unit) {
    val state = rememberSwipeToDismissBoxState()
    SwipeToDismissBox(
        state = state,
        backgroundContent = {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.errorContainer).padding(16.dp)) {
                Text("Delete", color = MaterialTheme.colorScheme.onErrorContainer)
            }
        },
        onDismiss = { onDelete() },
    ) { content() }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalTime::class)
@Composable
private fun ClipRow(clip: Clip, sourceIcon: @Composable (String) -> Unit, onClick: () -> Unit, onLongClick: () -> Unit) {
    val now = Clock.System.now().toEpochMilliseconds()
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(16.dp, 12.dp)
            .testTag("clip:${clip.content.take(40)}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(clip.contentType.glyph(), modifier = Modifier.width(40.dp))
        Column(Modifier.weight(1f)) {
            Text(preview(clip.content), maxLines = 2, overflow = TextOverflow.Ellipsis)
            val flags = listOfNotNull(relativeTime(now, clip.createdAt), "sensitive".takeIf { clip.sensitive })
            Text(flags.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
        }
        clip.sourcePackage?.let { sourceIcon(it) }
        if (clip.pinned) Text("📌", modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun EmptyState(requestAddTile: (() -> Unit)?) {
    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Nothing captured yet", style = MaterialTheme.typography.headlineSmall)
        Text("Android only lets the app on screen read the clipboard, so ClipVault captures when you ask:")
        Text("• Quick Settings tile: copy something, pull down the shade, tap ClipVault.")
        Text("• Notification action: turn on the capture notification in Settings.")
        Text("• Share sheet: share text to ClipVault from any app.")
        Text("• Text selection: select text and pick \"Save to ClipVault\".")
        Text("Optional: automatic capture via Accessibility (Settings explains the trade-offs).")
        requestAddTile?.let { add -> TextButton(onClick = add) { Text("Add the Quick Settings tile") } }
    }
}
