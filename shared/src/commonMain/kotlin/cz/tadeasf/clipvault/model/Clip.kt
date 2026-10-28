package cz.tadeasf.clipvault.model

enum class ContentType { PLAIN_TEXT, URL, CODE, EMAIL, PHONE, ADDRESS }

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
)

/** What capture hands to the repository; id, hash, clock and timestamps are assigned on insert. */
data class ClipDraft(
    val content: String,
    val contentType: ContentType = ContentType.PLAIN_TEXT,
    val sensitive: Boolean = false,
)

data class RetentionPolicy(val maxCount: Long? = null, val maxAgeMillis: Long? = null)
