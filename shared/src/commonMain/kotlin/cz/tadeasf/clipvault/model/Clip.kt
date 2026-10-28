package cz.tadeasf.clipvault.model

import cz.tadeasf.clipvault.detect.SensitiveCategory

/** Detection order lives in `detectContentType`: JSON, URL, EMAIL, PHONE, CODE, PLAIN. */
enum class ContentType { URL, EMAIL, PHONE, CODE, JSON, PLAIN }

/** A stored clip. Property order mirrors the `Clip` table so SQLDelight queries map with `::Clip`. */
data class Clip(
    val id: String,
    val deviceId: String,
    val createdAt: Long,
    val contentType: ContentType,
    val contentHash: String,
    val content: String,
    val sensitive: Boolean,
    val pinned: Boolean,
    val deleted: Boolean,
    val lamportClock: Long,
    val seq: Long,
    val categories: Set<SensitiveCategory>,
    /** Package of the copying app; only known on the Accessibility capture path. */
    val sourcePackage: String?,
)

/** Platform signals that come with a captured clip. All optional; capture paths fill what they know. */
data class ClipHints(
    /** Android `ClipDescription.EXTRA_IS_SENSITIVE`, set by the copying app. */
    val isSensitiveFlag: Boolean = false,
    /** Package of the copying app; null unless the Accessibility path is on. */
    val sourcePackage: String? = null,
    /** Title of the focused window on desktop, when the capture path can see it. */
    val sourceWindowTitle: String? = null,
)

/** What capture hands to the repository; type, sensitivity, id, hash, clock and seq are derived on insert. */
data class ClipDraft(val content: String, val hints: ClipHints = ClipHints())

/** What the history list shows. Blank [text] lists everything; [type] null means any content type. */
data class ClipFilter(
    val text: String = "",
    val type: ContentType? = null,
    val pinnedOnly: Boolean = false,
    val includeSensitive: Boolean = false,
)

data class InsertResult(val id: String, val duplicate: Boolean, val sensitive: Boolean)

data class ClipCounts(val total: Long, val sensitive: Long)

data class RetentionPolicy(
    val maxCount: Long? = null,
    val maxAgeMillis: Long? = null,
    val sensitiveTtlMillis: Long? = DEFAULT_SENSITIVE_TTL_MILLIS,
) {
    companion object {
        const val DEFAULT_SENSITIVE_TTL_MILLIS = 2 * 60 * 1000L
    }
}
