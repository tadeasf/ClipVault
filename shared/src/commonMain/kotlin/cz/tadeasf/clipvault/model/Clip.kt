package cz.tadeasf.clipvault.model

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

data class RetentionPolicy(
    val maxCount: Long? = null,
    val maxAgeMillis: Long? = null,
    val sensitiveTtlMillis: Long? = DEFAULT_SENSITIVE_TTL_MILLIS,
) {
    companion object {
        const val DEFAULT_SENSITIVE_TTL_MILLIS = 2 * 60 * 1000L
    }
}
