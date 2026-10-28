package cz.tadeasf.clipvault.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cz.tadeasf.clipvault.history.ClipIngest
import cz.tadeasf.clipvault.history.ClipRepository
import cz.tadeasf.clipvault.model.Clip
import cz.tadeasf.clipvault.transform.Transformation
import cz.tadeasf.clipvault.ui.Platform
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface DetailState {
    data object Loading : DetailState

    /** Deleted from here, or never existed: the screen navigates back. */
    data object Gone : DetailState

    data class Loaded(val clip: Clip, val message: String? = null) : DetailState {
        /** Pinning a sensitive clip keeps it past the TTL sweep, so the pin UI warns. */
        val pinWarning: Boolean get() = clip.sensitive
    }
}

class DetailViewModel(
    private val clipId: String,
    private val repository: ClipRepository,
    private val ingest: ClipIngest,
    private val platform: Platform,
) : ViewModel() {
    private val mutableState = MutableStateFlow<DetailState>(DetailState.Loading)
    val state: StateFlow<DetailState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch { reload() }
    }

    private suspend fun reload(message: String? = null) {
        mutableState.value = repository.get(clipId)?.takeUnless { it.deleted }
            ?.let { DetailState.Loaded(it, message) } ?: DetailState.Gone
    }

    fun copy() = withClip { clip ->
        platform.copyToClipboard(clip.content, clip.sensitive)
        say("Copied to clipboard")
    }

    fun share() = withClip { platform.share(it.content) }

    fun togglePin() = withClip { clip ->
        viewModelScope.launch {
            repository.setPinned(clip.id, !clip.pinned)
            reload(if (clip.pinned) "Unpinned" else "Pinned")
        }
    }

    fun delete() = withClip { clip ->
        viewModelScope.launch {
            repository.delete(clip.id)
            mutableState.value = DetailState.Gone
        }
    }

    /** Copies the result; with [saveAsNew] it is also stored as its own clip. Failures become a message. */
    fun apply(transformation: Transformation, saveAsNew: Boolean) = withClip { clip ->
        transformation(clip.content).fold(
            onSuccess = { result ->
                platform.copyToClipboard(result, clip.sensitive)
                if (saveAsNew) viewModelScope.launch { ingest.ingest(result) }
                say(if (saveAsNew) "Copied and saved as a new clip" else "Copied to clipboard")
            },
            onFailure = { say(it.message ?: "Can't apply to this clip") },
        )
    }

    fun messageShown() = say(null)

    private fun say(message: String?) = mutableState.update {
        if (it is DetailState.Loaded) it.copy(message = message) else it
    }

    private inline fun withClip(action: (Clip) -> Unit) {
        (mutableState.value as? DetailState.Loaded)?.let { action(it.clip) }
    }
}
