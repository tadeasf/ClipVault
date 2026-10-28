package cz.tadeasf.clipvault.model

enum class MetaField { PINNED, DELETED }

/** Property order mirrors `MetaEvent.forClip` so it maps with `::MetaEvent`. */
data class MetaEvent(
    val clipId: String,
    val field: MetaField,
    val value: Boolean,
    val lamportClock: Long,
    val deviceId: String,
)

data class ClipFlags(val pinned: Boolean, val deleted: Boolean)

private val newestFirst = compareBy<MetaEvent>({ it.lamportClock }, { it.deviceId })

/**
 * Current flags of one clip (design 5.1): pinned is last-writer-wins on (lamportClock, deviceId),
 * and any delete wins over every flag change regardless of clock.
 */
fun foldMeta(events: List<MetaEvent>): ClipFlags {
    val deleted = events.any { it.field == MetaField.DELETED && it.value }
    val lastPin = events.filter { it.field == MetaField.PINNED }.maxWithOrNull(newestFirst)
    return ClipFlags(pinned = !deleted && lastPin?.value == true, deleted = deleted)
}
