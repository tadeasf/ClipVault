package cz.tadeasf.clipvault.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import cz.tadeasf.clipvault.history.ClipRepository
import cz.tadeasf.clipvault.model.Clip
import cz.tadeasf.clipvault.model.ClipFilter
import cz.tadeasf.clipvault.model.ContentType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface HistoryState {
    data object Loading : HistoryState

    /** No clips at all yet: the screen explains the capture paths. */
    data object Empty : HistoryState

    data class Content(val filter: ClipFilter, val sensitiveCount: Long, val message: String? = null) : HistoryState
}

class HistoryViewModel(
    private val repository: ClipRepository,
    private val copyToClipboard: (Clip) -> Unit,
    private val sensitiveRevealMillis: Long = SENSITIVE_REVEAL_MILLIS,
) : ViewModel() {
    private val filter = MutableStateFlow(ClipFilter())
    private val message = MutableStateFlow<String?>(null)
    private var hideSensitiveJob: Job? = null

    val state: StateFlow<HistoryState> = combine(repository.counts(), filter, message) { counts, filter, message ->
        if (counts.total == 0L) HistoryState.Empty else HistoryState.Content(filter, counts.sensitive, message)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, HistoryState.Loading)

    @OptIn(ExperimentalCoroutinesApi::class)
    val clips: Flow<PagingData<Clip>> = filter
        .flatMapLatest { current -> Pager(PagingConfig(pageSize = PAGE_SIZE)) { repository.pagingSource(current) }.flow }
        .cachedIn(viewModelScope)

    fun onSearch(text: String) = filter.update { it.copy(text = text) }

    fun onTypeFilter(type: ContentType?) = filter.update { it.copy(type = type) }

    fun onPinnedOnly(pinnedOnly: Boolean) = filter.update { it.copy(pinnedOnly = pinnedOnly) }

    /** Reveals sensitive clips for [sensitiveRevealMillis], then hides them again on its own. */
    fun showSensitive() {
        filter.update { it.copy(includeSensitive = true) }
        hideSensitiveJob?.cancel()
        hideSensitiveJob = viewModelScope.launch {
            delay(sensitiveRevealMillis)
            hideSensitive()
        }
    }

    fun hideSensitive() {
        hideSensitiveJob?.cancel()
        filter.update { it.copy(includeSensitive = false) }
    }

    fun copy(clip: Clip) {
        copyToClipboard(clip)
        message.value = "Copied to clipboard"
    }

    fun delete(id: String) {
        viewModelScope.launch {
            repository.delete(id)
            message.value = "Deleted"
        }
    }

    fun messageShown() {
        message.value = null
    }

    companion object {
        const val SENSITIVE_REVEAL_MILLIS = 60_000L
        private const val PAGE_SIZE = 50
    }
}
