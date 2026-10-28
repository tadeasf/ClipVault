package cz.tadeasf.clipvault.history

import cz.tadeasf.clipvault.model.ClipDraft
import cz.tadeasf.clipvault.model.ClipHints

sealed interface IngestResult {
    data class Stored(val id: String) : IngestResult
    data class Skipped(val reason: SkipReason) : IngestResult
}

enum class SkipReason {
    BLANK,

    /** The copying app set EXTRA_IS_SENSITIVE: never stored at all (design 6.2). */
    MARKED_SENSITIVE_BY_SOURCE,
}

/**
 * The single entry point for every capture path (Android tile/share/Accessibility, desktop watcher).
 * Decides whether a clip is stored; classification and dedupe happen in the repository.
 */
class ClipIngest(private val repository: ClipRepository) {

    suspend fun ingest(text: String, hints: ClipHints = ClipHints()): IngestResult = when {
        text.isBlank() -> IngestResult.Skipped(SkipReason.BLANK)
        hints.isSensitiveFlag -> IngestResult.Skipped(SkipReason.MARKED_SENSITIVE_BY_SOURCE)
        else -> IngestResult.Stored(repository.insert(ClipDraft(text, hints)))
    }
}
