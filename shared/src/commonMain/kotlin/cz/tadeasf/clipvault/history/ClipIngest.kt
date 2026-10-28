package cz.tadeasf.clipvault.history

import cz.tadeasf.clipvault.detect.KnownSensitiveSources
import cz.tadeasf.clipvault.model.ClipDraft
import cz.tadeasf.clipvault.model.ClipHints

sealed interface IngestResult {
    data class Stored(val id: String, val duplicate: Boolean, val sensitive: Boolean) : IngestResult
    data class Skipped(val reason: SkipReason) : IngestResult
}

enum class SkipReason {
    BLANK,

    /**
     * The copying app set EXTRA_IS_SENSITIVE or is a known password manager: never stored (design 6.2).
     * The system clipboard still holds the value for pasting; ClipVault just doesn't record it.
     */
    SENSITIVE_SOURCE,

    /** The copying app is on the user's exclusion list (needs the Accessibility path to know the app). */
    EXCLUDED_SOURCE,
}

/**
 * The single entry point for every capture path (Android tile/share/Accessibility, desktop watcher).
 * Decides whether a clip is stored; classification and dedupe happen in the repository.
 */
class ClipIngest(
    private val repository: ClipRepository,
    private val excludedPackages: () -> Set<String> = { emptySet() },
) {

    suspend fun ingest(text: String, hints: ClipHints = ClipHints()): IngestResult = when {
        text.isBlank() -> IngestResult.Skipped(SkipReason.BLANK)
        KnownSensitiveSources.matches(hints) -> IngestResult.Skipped(SkipReason.SENSITIVE_SOURCE)
        hints.sourcePackage in excludedPackages() -> IngestResult.Skipped(SkipReason.EXCLUDED_SOURCE)
        else -> repository.insert(ClipDraft(text, hints)).let { IngestResult.Stored(it.id, it.duplicate, it.sensitive) }
    }
}
